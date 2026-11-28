package com.laotie.dialect;

import java.util.HashSet;
import java.util.Set;

import com.laotie.identifier.IdentifierCase;

/**
 * Date/time unit and date-part keywords accepted by the supported dialects.
 */
final class UnitKeywords {

    private static final Set<String> UNITS = new HashSet<>();
    private static final Set<String> JDBC_UNITS = new HashSet<>();

    static {
        java.util.Collections.addAll(UNITS,
                "YEAR", "QUARTER", "MONTH", "WEEK", "DAY", "HOUR", "MINUTE",
                "SECOND", "MILLISECOND", "MICROSECOND", "NANOSECOND",
                "DATE", "TIME", "DATETIME", "TIMESTAMP",
                "YEAR_MONTH", "DAY_HOUR", "DAY_MINUTE", "DAY_SECOND",
                "DAY_MICROSECOND", "HOUR_MINUTE", "HOUR_SECOND",
                "HOUR_MICROSECOND", "MINUTE_SECOND", "MINUTE_MICROSECOND",
                "SECOND_MICROSECOND",
                "YY", "YYYY", "QQ", "Q", "MM", "M", "DY", "Y", "DD", "D",
                "WK", "WW", "DW", "HH", "MI", "N", "SS", "S", "MS", "MCS",
                "NS", "ISOWW", "ISO_WEEK", "WEEKDAY", "DAYOFYEAR",
                "EPOCH", "DOW", "ISODOW", "DOY", "ISOYEAR", "CENTURY",
                "DECADE", "MILLENNIUM", "MICROSECONDS", "MILLISECONDS",
                "TIMEZONE", "TIMEZONE_HOUR", "TIMEZONE_MINUTE", "JULIAN");
        java.util.Collections.addAll(JDBC_UNITS,
                "SQL_TSI_FRAC_SECOND", "SQL_TSI_SECOND", "SQL_TSI_MINUTE",
                "SQL_TSI_HOUR", "SQL_TSI_DAY", "SQL_TSI_WEEK",
                "SQL_TSI_MONTH", "SQL_TSI_QUARTER", "SQL_TSI_YEAR",
                "SQL_TSI_MILLI_SECOND", "SQL_TSI_MICRO_SECOND",
                "SQL_TSI_NANO_SECOND");
    }

    private UnitKeywords() {
    }

    static boolean isUnit(String name) {
        return name != null && UNITS.contains(
                IdentifierCase.upper(name));
    }

    static boolean isJdbcUnit(String name) {
        return name != null && JDBC_UNITS.contains(
                IdentifierCase.upper(name));
    }

    static String upper(String name) {
        return IdentifierCase.upper(name);
    }
}
