package com.campushire.campushire.utils.excel.annotation;

import java.lang.annotation.*;

/**
 * Marks a field as an Excel column for import/export.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ExcelColumn {

    /**
     * Column header in the Excel sheet.
     */
    String value();

    /**
     * Column order while exporting.
     */
    int order() default Integer.MAX_VALUE;

    /**
     * Whether this column is required during import.
     */
    boolean required() default false;

    /**
     * Date format used for import/export.
     */
    String format() default "";

    /**
     * Column width (characters).
     */
    int width() default 20;

    /**
     * Ignore this field while importing.
     */
    boolean ignoreImport() default false;

    /**
     * Ignore this field while exporting.
     */
    boolean ignoreExport() default false;

    /**
     * Example value for generated templates.
     */
    String example() default "";
}