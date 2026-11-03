package dev.apitestingplatform.domain.run;

public record HistorySettings(int maxEntries, int maxAgeDays) {
    public HistorySettings { if (maxEntries < 1 || maxEntries > 1000 || maxAgeDays < 1 || maxAgeDays > 365) throw new IllegalArgumentException("History retention: 1–1,000 records and 1–365 days."); }
    public static HistorySettings defaults() { return new HistorySettings(100, 30); }
}
