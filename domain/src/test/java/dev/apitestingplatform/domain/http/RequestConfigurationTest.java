package dev.apitestingplatform.domain.http;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;

class RequestConfigurationTest {
    @Test void structuredBodiesCopyListsAndRejectMixedModes() {
        var fields = new ArrayList<RequestField>();
        fields.add(new RequestField("key", "value", false));
        RequestBody body = RequestBody.form(fields);
        fields.clear();
        assertThat(body.fields()).hasSize(1);
        assertThat(body.fields().getFirst().enabled()).isFalse();
        assertThatThrownBy(() -> body.fields().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RequestBody(RequestBody.Type.TEXT, "text", body.fields(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void executionResultCannotBeMutatedThroughOriginalOrReturnedContainers() {
        List<String> values = new ArrayList<>(List.of("value"));
        byte[] bytes = {1, 2};
        ExecutionResult result = new ExecutionResult(200, Map.of("x-test", values), bytes, 1, 2,
                Optional.empty(), Instant.now(), Optional.of(HttpProtocol.HTTP_1_1));
        values.clear();
        bytes[0] = 9;
        assertThat(result.responseHeaders().get("x-test")).containsExactly("value");
        assertThat(result.responseBody()).containsExactly((byte) 1, (byte) 2);
        assertThatThrownBy(() -> result.responseHeaders().get("x-test").clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.responseHeaders().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void defensivelyCopiesInputsAndValidatesTimeout() {
        List<RequestField> fields = new ArrayList<>(List.of(new RequestField("q", "value")));
        RequestConfiguration config = new RequestConfiguration(HttpMethod.GET, "http://localhost", fields,
                List.of(), List.of(), RequestBody.none(), Duration.ofSeconds(1), RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
        fields.clear();
        assertThat(config.queryParameters()).hasSize(1);
        assertThatThrownBy(() -> config.queryParameters().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new RequestConfiguration(HttpMethod.GET, "http://localhost", List.of(), List.of(),
                List.of(), RequestBody.none(), Duration.ZERO, RedirectPolicy.NORMAL, HttpProtocol.HTTP_2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
