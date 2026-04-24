package ru.exiledproject.developments.script;

import java.util.Map;

public record ScriptLeaderboardDefinition(
        ScriptNoDataPolicy noDataPolicy,
        Map<String, PeriodDefinition> periods
) {
    public boolean enabled() {
        return periods != null && !periods.isEmpty();
    }

    public record PeriodDefinition(
            String id,
            PeriodMode mode,
            String pattern,
            String zoneId
    ) {
    }

    public enum PeriodMode {
        ALLTIME,
        HOUR,
        DAY,
        WEEK,
        MONTH,
        YEAR,
        PATTERN
    }
}
