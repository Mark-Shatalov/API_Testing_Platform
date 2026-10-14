package dev.apitestingplatform.ui.response;

import dev.apitestingplatform.domain.http.ExecutionResult;
import dev.apitestingplatform.domain.http.NetworkError;

public enum ResponseState {
    EMPTY, LOADING, SUCCESS, HTTP_ERROR, NETWORK_ERROR, TIMEOUT, CANCELLED, RESPONSE_TOO_LARGE;

    public static ResponseState of(ExecutionResult result) {
        if (result.sizeBytes() > ResponsePresentation.BODY_LIMIT || result.bodyTruncated()) return RESPONSE_TOO_LARGE;
        if (result.error().isPresent()) {
            return switch (result.error().orElseThrow().type()) {
                case TIMEOUT -> TIMEOUT;
                case CANCELLED -> CANCELLED;
                default -> NETWORK_ERROR;
            };
        }
        return result.statusCode() >= 400 ? HTTP_ERROR : SUCCESS;
    }
}
