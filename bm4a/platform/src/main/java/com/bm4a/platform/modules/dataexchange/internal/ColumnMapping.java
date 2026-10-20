package com.bm4a.platform.modules.dataexchange.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bm4a.platform.modules.access.FieldPolicy;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldValues;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;

/**
 * Column headers and their mapping to fields. Headers are field labels; a label shared by several fields becomes
 * {@code Label (api_key)}. Suggestions match, in this order: the exact API key, the label ignoring case, spaces and
 * punctuation, the {@code (api_key)} suffix, and the API key ignoring case and punctuation.
 */
final class ColumnMapping {

    private static final Pattern KEY_SUFFIX = Pattern.compile("\\(([a-z][a-z0-9_]{0,62})\\)\\s*$");

    /** A mapped column of an import. */
    record Column(int index, String header, FieldSchema field) {
    }

    private ColumnMapping() {
    }

    /** Fields the caller may import: active, not read-only, and neither hidden nor read-only for them. */
    static List<FieldSchema> importable(ModuleSchema module, List<FieldPolicy> policies) {
        return FieldValues.ordered(module.fields()).stream()
                .filter(field -> field.status() == FieldStatus.ACTIVE && !field.readOnly())
                .filter(field -> policies.stream().noneMatch(policy -> policy.hidden(field.apiKey())
                        || policy.readOnly(field.apiKey())))
                .toList();
    }

    /** Column header of each field, in the order given. */
    static Map<FieldSchema, String> headers(List<FieldSchema> fields) {
        Map<String, Integer> labels = new HashMap<>();
        fields.forEach(field -> labels.merge(normalize(field.label()), 1, Integer::sum));
        Map<FieldSchema, String> headers = new LinkedHashMap<>();
        for (FieldSchema field : fields) {
            headers.put(field, labels.get(normalize(field.label())) > 1 ? field.label() + " (" + field.apiKey() + ")"
                    : field.label());
        }
        return headers;
    }

    /** The header row of a file: trimmed names; blank names are allowed (unmappable), duplicates are not. */
    static List<String> headerRow(List<String> cells) {
        List<String> headers = cells.stream().map(String::strip).toList();
        Set<String> seen = new HashSet<>();
        for (String header : headers) {
            if (!header.isEmpty() && !seen.add(header)) {
                throw ApiException.badRequest("duplicate_header", "Column '" + header + "' appears more than once");
            }
        }
        if (seen.isEmpty()) {
            throw ApiException.badRequest("empty_file", "The file has no header row");
        }
        return headers;
    }

    /** {@code header -> field key} for the headers that match an importable field (each field at most once). */
    static Map<String, String> suggest(List<String> headers, List<FieldSchema> fields) {
        Map<String, String> mapping = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (int rule = 0; rule < 4; rule++) {
            for (String header : headers) {
                if (header.isEmpty() || mapping.containsKey(header)) {
                    continue;
                }
                for (FieldSchema field : fields) {
                    if (!used.contains(field.apiKey()) && matches(rule, header, field)) {
                        mapping.put(header, field.apiKey());
                        used.add(field.apiKey());
                        break;
                    }
                }
            }
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        headers.stream().filter(mapping::containsKey).forEach(header -> ordered.put(header, mapping.get(header)));
        return ordered;
    }

    private static boolean matches(int rule, String header, FieldSchema field) {
        return switch (rule) {
            case 0 -> header.equals(field.apiKey());
            case 1 -> normalize(header).equals(normalize(field.label()));
            case 2 -> {
                Matcher suffix = KEY_SUFFIX.matcher(header);
                yield suffix.find() && suffix.group(1).equals(field.apiKey());
            }
            default -> normalize(header).equals(normalize(field.apiKey()));
        };
    }

    /**
     * Validates a requested mapping against the file headers and the importable fields.
     *
     * @param mapping {@code header -> field key}; a {@code null} or blank key leaves the column out
     * @throws ApiException 400 {@code validation_failed} with {@code mapping.<header>} or {@code matchField} paths
     */
    static List<Column> resolve(ModuleSchema module, List<String> headers, Map<String, String> mapping,
            ImportMode mode, String matchField, List<FieldPolicy> policies) {
        List<FieldViolation> violations = new ArrayList<>();
        List<Column> columns = new ArrayList<>();
        Set<String> mapped = new HashSet<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String header = entry.getKey();
            String key = entry.getValue();
            if (key == null || key.isBlank()) {
                continue;
            }
            String path = "mapping." + header;
            int index = header == null || header.isBlank() ? -1 : headers.indexOf(header.strip());
            FieldSchema field = module.field(key).filter(FieldSchema::visible).orElse(null);
            if (index < 0) {
                violations.add(new FieldViolation(path, "unknown_column", "the file has no such column"));
            } else if (field == null || policies.stream().anyMatch(policy -> policy.hidden(key))) {
                violations.add(new FieldViolation(path, "unknown_field", "unknown field '" + key + "'"));
            } else if (field.status() != FieldStatus.ACTIVE || field.readOnly()
                    || policies.stream().anyMatch(policy -> policy.readOnly(key))) {
                violations.add(new FieldViolation(path, "field_read_only", "field '" + key + "' cannot be imported"));
            } else if (!mapped.add(key)) {
                violations.add(new FieldViolation(path, "duplicate_field", "field '" + key + "' is mapped twice"));
            } else {
                columns.add(new Column(index, headers.get(index), field));
            }
        }
        if (mode == ImportMode.UPSERT) {
            FieldSchema match = matchField == null ? null : module.field(matchField).filter(FieldSchema::visible)
                    .orElse(null);
            if (match == null || !match.unique()) {
                violations.add(new FieldViolation("matchField", "match_field", "UPSERT needs a unique field to match"));
            } else if (!mapped.contains(matchField)) {
                violations.add(new FieldViolation("matchField", "match_field", "the match field must be mapped"));
            }
        } else if (matchField != null) {
            violations.add(new FieldViolation("matchField", "match_field", "matchField is only used with UPSERT"));
        }
        if (mode == ImportMode.CREATE) {
            for (FieldSchema field : FieldValues.ordered(module.fields())) {
                if (field.status() == FieldStatus.ACTIVE && field.required() && field.defaultValue() == null
                        && !mapped.contains(field.apiKey())) {
                    violations.add(new FieldViolation("mapping", "required_field_unmapped",
                            "required field '" + field.apiKey() + "' has no column"));
                }
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        if (columns.isEmpty()) {
            throw ApiException.badRequest("empty_mapping", "No column is mapped to a field");
        }
        columns.sort((a, b) -> Integer.compare(a.index(), b.index()));
        return columns;
    }

    static String normalize(String value) {
        StringBuilder normalized = new StringBuilder();
        value.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit)
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }
}
