package com.bm4a.platform.modules.metadata;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Type-specific field settings, stored as the {@code config} JSON object. Which properties are allowed depends on the
 * field type (validated when the definition is written).
 *
 * @param minLength text types: minimum length after trimming
 * @param maxLength text types: maximum length (defaults: 255 text, 10000 long text)
 * @param min numeric types and currency amount: inclusive lower bound
 * @param max numeric types and currency amount: inclusive upper bound
 * @param scale decimal: maximum fraction digits (default 6)
 * @param options select types: allowed values
 * @param maxSelections multi-select: maximum number of values
 * @param currencies currency: allowed ISO 4217 codes (empty = any)
 * @param maxBytes JSON: maximum serialized size (default 16384)
 * @param targetModuleKey lookup: API key of the referenced module (tenant or platform-global)
 * @param onTargetDelete lookup: {@code RESTRICT} (default) blocks deleting a referenced record; {@code NONE} allows it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldConfig(Integer minLength, Integer maxLength, BigDecimal min, BigDecimal max, Integer scale,
        List<SelectOption> options, Integer maxSelections, List<String> currencies, Integer maxBytes,
        String targetModuleKey, String onTargetDelete) {

    public static final String RESTRICT = "RESTRICT";
    public static final String NONE = "NONE";

    public static FieldConfig empty() {
        return new FieldConfig(null, null, null, null, null, null, null, null, null, null, null);
    }

    public List<SelectOption> optionsOrEmpty() {
        return options == null ? List.of() : options;
    }

    public boolean restrictsTargetDelete() {
        return !NONE.equals(onTargetDelete);
    }

    /** {@code deprecated} is {@code true} or absent ({@code null}), never {@code false}, so equality is stable. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SelectOption(String value, String label, Boolean deprecated) {

        public SelectOption {
            deprecated = Boolean.TRUE.equals(deprecated) ? Boolean.TRUE : null;
        }

        public boolean isDeprecated() {
            return Boolean.TRUE.equals(deprecated);
        }
    }
}
