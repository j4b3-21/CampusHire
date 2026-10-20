package com.bm4a.platform.modules.metadata.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldConfig.SelectOption;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.FieldValues;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates field definitions and enforces the schema evolution rules of ADR-001: immutable API key, uniqueness flag
 * and lookup target; compatible type changes only; select options may be deprecated but never removed.
 */
@Component
class FieldDefinitionValidator {

    static final Pattern API_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    static final Set<String> RESERVED_KEYS = Set.of("id", "version", "status", "data", "module", "organization",
            "organization_id", "module_id", "created_at", "created_by", "updated_at", "updated_by", "deleted_at",
            "deleted_by");
    private static final Pattern OPTION_VALUE = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    private static final Set<FieldType> TEXT_TYPES = EnumSet.of(FieldType.TEXT, FieldType.LONG_TEXT);
    private static final Set<FieldType> SEARCHABLE = EnumSet.of(FieldType.TEXT, FieldType.LONG_TEXT, FieldType.EMAIL,
            FieldType.PHONE, FieldType.URL);
    private static final int MAX_OPTIONS = 500;

    private final JsonMapper jsonMapper;

    FieldDefinitionValidator(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** Resolved, validated field properties ready to persist. */
    record FieldDraft(String apiKey, String label, String description, FieldType type, boolean required,
            boolean unique, boolean readOnly, boolean sensitive, boolean searchable, boolean filterable,
            boolean sortable, JsonNode defaultValue, FieldConfig config, int position) {
    }

    FieldDraft validateNew(FieldInput in, Function<String, Optional<ModuleRow>> lookupTargets, String prefix) {
        List<FieldViolation> violations = new ArrayList<>();
        if (in.apiKey() == null || !API_KEY.matcher(in.apiKey()).matches()) {
            violations.add(v(prefix + "apiKey", "format", "apiKey must match ^[a-z][a-z0-9_]{0,62}$"));
        } else if (RESERVED_KEYS.contains(in.apiKey())) {
            violations.add(v(prefix + "apiKey", "reserved", "apiKey '" + in.apiKey() + "' is reserved"));
        }
        if (in.type() == null) {
            violations.add(v(prefix + "type", "required", "type is required"));
            throw ApiException.validation(violations);
        }
        FieldDraft draft = new FieldDraft(in.apiKey(), in.label(), in.description(), in.type(), bool(in.required()),
                bool(in.unique()), bool(in.readOnly()), bool(in.sensitive()), bool(in.searchable()),
                bool(in.filterable()), bool(in.sortable()), nullIfJsonNull(in.defaultValue()),
                parseConfig(in.config(), prefix, violations), in.position() == null ? 0 : in.position());
        validateCommon(draft, lookupTargets, prefix, violations);
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        return withCanonicalDefault(draft);
    }

    FieldDraft validateUpdate(FieldRow current, FieldInput in, Function<String, Optional<ModuleRow>> lookupTargets) {
        List<FieldViolation> violations = new ArrayList<>();
        if (in.apiKey() != null && !in.apiKey().equals(current.apiKey())) {
            violations.add(v("apiKey", "immutable", "apiKey cannot be changed; create a new field instead"));
        }
        if (in.unique() != null && in.unique() != current.unique()) {
            throw ApiException.conflict("unique_immutable",
                    "unique cannot be changed after creation; create a new field and backfill it");
        }
        FieldType type = in.type() == null ? current.type() : in.type();
        if (!current.type().canChangeTo(type)) {
            throw ApiException.conflict("incompatible_type_change", "Changing " + current.type() + " to " + type
                    + " could invalidate stored values; create a new field and backfill it");
        }
        FieldConfig config = in.config() == null ? current.config() : parseConfig(in.config(), "", violations);
        JsonNode defaultValue = in.defaultValue() == null ? current.defaultValue() : nullIfJsonNull(in.defaultValue());
        FieldDraft draft = new FieldDraft(current.apiKey(), in.label() == null ? current.label() : in.label(),
                in.description() == null ? current.description() : in.description(), type,
                in.required() == null ? current.required() : in.required(), current.unique(),
                in.readOnly() == null ? current.readOnly() : in.readOnly(),
                in.sensitive() == null ? current.sensitive() : in.sensitive(),
                in.searchable() == null ? current.searchable() : in.searchable(),
                in.filterable() == null ? current.filterable() : in.filterable(),
                in.sortable() == null ? current.sortable() : in.sortable(), defaultValue, config,
                in.position() == null ? current.position() : in.position());
        if (violations.isEmpty()) {
            validateCommon(draft, lookupTargets, "", violations);
            validateEvolution(current, draft, violations);
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        return withCanonicalDefault(draft);
    }

    private void validateCommon(FieldDraft f, Function<String, Optional<ModuleRow>> lookupTargets, String p,
            List<FieldViolation> violations) {
        if (f.label() == null || f.label().isBlank() || f.label().length() > 200) {
            violations.add(v(p + "label", "size", "label must be 1-200 characters"));
        }
        if (f.description() != null && f.description().length() > 2000) {
            violations.add(v(p + "description", "size", "description must be at most 2000 characters"));
        }
        if (f.position() < 0 || f.position() > 10_000) {
            violations.add(v(p + "position", "range", "position must be between 0 and 10000"));
        }
        if (f.unique() && !f.type().supportsUnique()) {
            violations.add(v(p + "unique", "unsupported", "unique is not supported for " + f.type()));
        }
        if (f.filterable() && !f.type().supportsFilter()) {
            violations.add(v(p + "filterable", "unsupported", "filtering is not supported for " + f.type()));
        }
        if (f.sortable() && !f.type().supportsSort()) {
            violations.add(v(p + "sortable", "unsupported", "sorting is not supported for " + f.type()));
        }
        if (f.searchable() && !SEARCHABLE.contains(f.type())) {
            violations.add(v(p + "searchable", "unsupported", "text search is not supported for " + f.type()));
        }
        if (f.defaultValue() != null) {
            if (!f.type().supportsDefault() || f.unique()) {
                violations.add(v(p + "defaultValue", "unsupported", "a default value is not supported for this field"));
            } else if (f.config() != null) {
                FieldValues.Outcome outcome = FieldValues.canonicalize(schemaOf(f), f.defaultValue(), null);
                if (!outcome.valid()) {
                    violations.add(v(p + "defaultValue", outcome.code(), "defaultValue " + outcome.message()));
                }
            }
        }
        if (f.required() && f.readOnly() && f.defaultValue() == null) {
            violations.add(v(p + "defaultValue", "required", "required read-only fields need a default value"));
        }
        if (f.config() != null) {
            validateConfig(f.type(), f.config(), lookupTargets, p + "config.", violations);
        }
    }

    private void validateConfig(FieldType type, FieldConfig c, Function<String, Optional<ModuleRow>> lookupTargets,
            String p, List<FieldViolation> violations) {
        requireAbsentUnless(TEXT_TYPES.contains(type), c.minLength(), p + "minLength", violations);
        requireAbsentUnless(TEXT_TYPES.contains(type), c.maxLength(), p + "maxLength", violations);
        boolean numeric = type == FieldType.INTEGER || type == FieldType.DECIMAL || type == FieldType.CURRENCY;
        requireAbsentUnless(numeric, c.min(), p + "min", violations);
        requireAbsentUnless(numeric, c.max(), p + "max", violations);
        requireAbsentUnless(type == FieldType.DECIMAL, c.scale(), p + "scale", violations);
        boolean select = type == FieldType.SINGLE_SELECT || type == FieldType.MULTI_SELECT;
        requireAbsentUnless(select, c.options(), p + "options", violations);
        requireAbsentUnless(type == FieldType.MULTI_SELECT, c.maxSelections(), p + "maxSelections", violations);
        requireAbsentUnless(type == FieldType.CURRENCY, c.currencies(), p + "currencies", violations);
        requireAbsentUnless(type == FieldType.JSON, c.maxBytes(), p + "maxBytes", violations);
        requireAbsentUnless(type == FieldType.LOOKUP, c.targetModuleKey(), p + "targetModuleKey", violations);
        requireAbsentUnless(type == FieldType.LOOKUP, c.onTargetDelete(), p + "onTargetDelete", violations);

        if (TEXT_TYPES.contains(type)) {
            int ceiling = type == FieldType.TEXT ? 1000 : 100_000;
            if (c.maxLength() != null && (c.maxLength() < 1 || c.maxLength() > ceiling)) {
                violations.add(v(p + "maxLength", "range", "maxLength must be between 1 and " + ceiling));
            }
            if (c.minLength() != null && (c.minLength() < 0
                    || c.minLength() > (c.maxLength() == null ? ceiling : c.maxLength()))) {
                violations.add(v(p + "minLength", "range", "minLength must be between 0 and maxLength"));
            }
        }
        if (c.min() != null && c.max() != null && c.min().compareTo(c.max()) > 0) {
            violations.add(v(p + "min", "range", "min must be <= max"));
        }
        if (type == FieldType.INTEGER) {
            for (BigDecimal bound : new BigDecimal[] { c.min(), c.max() }) {
                if (bound != null && bound.stripTrailingZeros().scale() > 0) {
                    violations.add(v(p + "min", "type", "integer bounds must be whole numbers"));
                }
            }
        }
        if (c.scale() != null && (c.scale() < 0 || c.scale() > 18)) {
            violations.add(v(p + "scale", "range", "scale must be between 0 and 18"));
        }
        if (select) {
            List<SelectOption> options = c.optionsOrEmpty();
            if (options.isEmpty() || options.size() > MAX_OPTIONS) {
                violations.add(v(p + "options", "size", "options must contain 1-" + MAX_OPTIONS + " entries"));
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < options.size(); i++) {
                SelectOption option = options.get(i);
                if (option == null || option.value() == null || !OPTION_VALUE.matcher(option.value()).matches()) {
                    violations.add(v(p + "options[" + i + "].value", "format",
                            "option value must match ^[A-Za-z0-9_.-]{1,100}$"));
                } else if (!seen.add(option.value())) {
                    violations.add(v(p + "options[" + i + "].value", "duplicate", "duplicate option value"));
                }
                if (option != null && (option.label() == null || option.label().isBlank()
                        || option.label().length() > 200)) {
                    violations.add(v(p + "options[" + i + "].label", "size", "option label must be 1-200 characters"));
                }
            }
            if (c.maxSelections() != null && (c.maxSelections() < 1 || c.maxSelections() > options.size())) {
                violations.add(v(p + "maxSelections", "range", "maxSelections must be between 1 and the option count"));
            }
        }
        if (c.currencies() != null) {
            for (String code : c.currencies()) {
                try {
                    if (code == null || !Currency.getInstance(code).getCurrencyCode().equals(code)) {
                        throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException ex) {
                    violations.add(v(p + "currencies", "format", "'" + code + "' is not an ISO 4217 code"));
                }
            }
        }
        if (c.maxBytes() != null && (c.maxBytes() < 1 || c.maxBytes() > 65_536)) {
            violations.add(v(p + "maxBytes", "range", "maxBytes must be between 1 and 65536"));
        }
        if (type == FieldType.LOOKUP) {
            if (c.targetModuleKey() == null) {
                violations.add(v(p + "targetModuleKey", "required", "lookup fields need targetModuleKey"));
            } else if (lookupTargets.apply(c.targetModuleKey()).filter(ModuleRow::active).isEmpty()) {
                violations.add(v(p + "targetModuleKey", "unknown_module",
                        "target module '" + c.targetModuleKey() + "' does not exist in this scope"));
            }
            if (c.onTargetDelete() != null && !Set.of(FieldConfig.RESTRICT, FieldConfig.NONE).contains(c.onTargetDelete())) {
                violations.add(v(p + "onTargetDelete", "format", "onTargetDelete must be RESTRICT or NONE"));
            }
        }
    }

    private static void validateEvolution(FieldRow current, FieldDraft draft, List<FieldViolation> violations) {
        if (current.type() == FieldType.LOOKUP && draft.config() != null
                && !java.util.Objects.equals(current.config().targetModuleKey(), draft.config().targetModuleKey())) {
            violations.add(v("config.targetModuleKey", "immutable", "the lookup target cannot be changed"));
        }
        Set<String> newValues = new HashSet<>();
        draft.config().optionsOrEmpty().forEach(option -> newValues.add(option.value()));
        for (SelectOption existing : current.config().optionsOrEmpty()) {
            if (!newValues.contains(existing.value())) {
                violations.add(v("config.options", "option_removed", "option '" + existing.value()
                        + "' is in use by stored records; mark it deprecated instead of removing it"));
            }
        }
    }

    private FieldConfig parseConfig(JsonNode config, String prefix, List<FieldViolation> violations) {
        if (config == null || config.isNull()) {
            return FieldConfig.empty();
        }
        if (!config.isObject()) {
            violations.add(v(prefix + "config", "type", "config must be a JSON object"));
            return FieldConfig.empty();
        }
        try {
            return jsonMapper.treeToValue(config, FieldConfig.class);
        } catch (RuntimeException ex) {
            violations.add(v(prefix + "config", "invalid", "config contains unknown or malformed properties"));
            return FieldConfig.empty();
        }
    }

    private FieldDraft withCanonicalDefault(FieldDraft f) {
        if (f.defaultValue() == null) {
            return f;
        }
        JsonNode canonical = FieldValues.canonicalize(schemaOf(f), f.defaultValue(), null).value();
        return new FieldDraft(f.apiKey(), f.label(), f.description(), f.type(), f.required(), f.unique(),
                f.readOnly(), f.sensitive(), f.searchable(), f.filterable(), f.sortable(), canonical, f.config(),
                f.position());
    }

    private static FieldSchema schemaOf(FieldDraft f) {
        return new FieldSchema(null, f.apiKey(), f.label(), f.type(), FieldStatus.ACTIVE, f.required(), f.unique(),
                f.readOnly(), f.sensitive(), f.searchable(), f.filterable(), f.sortable(), null, f.config(),
                f.position());
    }

    private static void requireAbsentUnless(boolean allowed, Object value, String field,
            List<FieldViolation> violations) {
        if (!allowed && value != null) {
            violations.add(v(field, "unsupported", "not applicable to this field type"));
        }
    }

    private static JsonNode nullIfJsonNull(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static boolean bool(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static FieldViolation v(String field, String code, String message) {
        return new FieldViolation(field, code, message);
    }
}
