package dev.apitestingplatform.application.http;

import dev.apitestingplatform.domain.http.ExecutionResult;
import java.util.concurrent.CompletionStage;

/** Cancellation completes the result normally with a CANCELLED network error. */
public interface RequestExecution {
    CompletionStage<ExecutionResult> result();
    boolean cancel();
}
