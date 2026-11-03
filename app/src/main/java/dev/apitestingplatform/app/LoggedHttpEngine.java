package dev.apitestingplatform.app;

import dev.apitestingplatform.application.http.*;
import dev.apitestingplatform.application.operations.EventLog;
import dev.apitestingplatform.domain.http.*;
import java.util.*;

/** Logs only classified outcomes and numeric measurements, never the submitted configuration. */
final class LoggedHttpEngine implements HttpExecutionEngine {
    private final HttpExecutionEngine delegate; private final EventLog log;
    LoggedHttpEngine(HttpExecutionEngine delegate, EventLog log) { this.delegate = delegate; this.log = log; }
    public RequestExecution execute(RequestConfiguration request) {
        UUID id = UUID.randomUUID(); log.record(EventLog.Level.INFO, EventLog.Component.HTTP, EventLog.Code.REQUEST_STARTED, id);
        try {
            var operation = delegate.execute(request);
            var logged = operation.result().whenComplete((response, failure) -> {
                if (failure != null) log.record(EventLog.Level.ERROR, EventLog.Component.HTTP, EventLog.Code.REQUEST_FAILED, id);
                else log.record(response.error().isPresent() || response.statusCode() >= 400 ? EventLog.Level.WARN : EventLog.Level.INFO, EventLog.Component.HTTP,
                        response.error().isPresent() ? EventLog.Code.REQUEST_FAILED : EventLog.Code.REQUEST_FINISHED, id,
                        Map.of(EventLog.Metric.HTTP_STATUS, (long) response.statusCode(), EventLog.Metric.DURATION_MS, response.durationMillis(), EventLog.Metric.CAPTURED_BYTES, response.sizeBytes()));
            });
            return new RequestExecution() {
                public java.util.concurrent.CompletionStage<ExecutionResult> result() { return logged; }
                public boolean cancel() { return operation.cancel(); }
            };
        } catch (RuntimeException failure) { log.record(EventLog.Level.ERROR, EventLog.Component.HTTP, EventLog.Code.REQUEST_FAILED, id); throw failure; }
    }
    public void close() { delegate.close(); }
}
