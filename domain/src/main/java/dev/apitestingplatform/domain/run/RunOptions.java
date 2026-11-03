package dev.apitestingplatform.domain.run;

public record RunOptions(int iterations, long delayMillis) {
    public RunOptions {
        if (iterations < 1 || iterations > 1000 || delayMillis < 0 || delayMillis > 60000)
            throw new IllegalArgumentException("Use 1–1,000 iterations and a delay of 0–60,000 milliseconds.");
    }
    public int total(int requestCount) {
        long total = (long) iterations * requestCount;
        if (total < 1 || total > 1000) throw new IllegalArgumentException("A run must contain 1–1,000 request executions in total. Reduce the iteration count.");
        return (int) total;
    }
}
