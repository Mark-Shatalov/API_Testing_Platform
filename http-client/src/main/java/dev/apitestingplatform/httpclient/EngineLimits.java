package dev.apitestingplatform.httpclient;

/** Limits apply per engine; requests exceeding admission capacity are rejected immediately. */
public record EngineLimits(int maxConcurrentRequests, int workerThreads, int maxResponseBytes,
                           int maxRequestBodyBytes, int maxRedirects) {
    public EngineLimits {
        if (maxConcurrentRequests < 1 || workerThreads < 1 || maxResponseBytes < 1
                || maxRequestBodyBytes < 1 || maxRedirects < 0 || maxRedirects > 100) {
            throw new IllegalArgumentException("Engine limits must be positive; redirects must be 0–100");
        }
    }
    public static EngineLimits defaults() { return new EngineLimits(16, 4, 10 * 1024 * 1024, 8 * 1024 * 1024, 10); }
}
