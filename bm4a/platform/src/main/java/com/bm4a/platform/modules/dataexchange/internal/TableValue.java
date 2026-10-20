package com.bm4a.platform.modules.dataexchange.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A typed cell value; XLSX writes native cells, CSV writes text. */
sealed interface TableValue {

    TableValue EMPTY = new Empty();

    static TableValue text(String value) {
        return value == null || value.isEmpty() ? EMPTY : new Text(value);
    }

    record Empty() implements TableValue {
    }

    /** Formula-like text is escaped on output ({@link Cells}). */
    record Text(String value) implements TableValue {
    }

    record Number(BigDecimal value) implements TableValue {
    }

    record Bool(boolean value) implements TableValue {
    }

    record Date(LocalDate value) implements TableValue {
    }

    /** A local date-time in the export time zone. */
    record DateTime(LocalDateTime value) implements TableValue {
    }

    record Money(BigDecimal amount, String currency) implements TableValue {
    }
}
