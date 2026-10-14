package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.RequestConfiguration;

public interface HttpExecutionEngine extends AutoCloseable {
    RequestExecution execute(RequestConfiguration request);
    @Override void close();
}
