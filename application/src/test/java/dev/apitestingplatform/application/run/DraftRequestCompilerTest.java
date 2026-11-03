package dev.apitestingplatform.application.run;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.RequestDraft;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DraftRequestCompilerTest {
    private static RequestDraft draft(String timeout, RequestBody.Type type, List<RequestDraft.Part> parts) {
        return new RequestDraft(HttpMethod.POST, "http://localhost/", List.of(), List.of(), List.of(), type, "{\"ok\":true}", List.of(new RequestField("name", "value")), parts,
                timeout, RedirectPolicy.NORMAL, HttpProtocol.HTTP_2);
    }
    @Test void supportsEverySavedBodyModeWithoutJavaFxAndKeepsDraftUnchanged() {
        for (var type : RequestBody.Type.values()) {
            var saved = draft("0.125", type, List.of(new RequestDraft.Part("name", "value", false, "unused", true)));
            var compiled = DraftRequestCompiler.compile(saved);
            assertThat(compiled.issues()).isEmpty(); assertThat(compiled.request().body().type()).isEqualTo(type);
            assertThat(compiled.request().timeout().toMillis()).isEqualTo(125); assertThat(saved.bodyText()).isEqualTo("{\"ok\":true}");
        }
    }
    @Test void rejectsInvalidTimeoutAndEnabledFilePathsButPreservesDisabledDrafts() {
        for (String invalid : List.of("NaN", "0", "-1", "86401", "0.0000000001")) assertThat(DraftRequestCompiler.compile(draft(invalid, RequestBody.Type.NONE, List.of())).issues()).singleElement().satisfies(issue -> assertThat(issue.field()).isEqualTo("timeout"));
        assertThat(DraftRequestCompiler.compile(draft("30", RequestBody.Type.MULTIPART, List.of(new RequestDraft.Part("file", "", true, "application/octet-stream", true)))).issues()).isNotEmpty();
        assertThat(DraftRequestCompiler.compile(draft("30", RequestBody.Type.MULTIPART, List.of(new RequestDraft.Part("file", "", true, "application/octet-stream", false)))).issues()).isEmpty();
    }
}
