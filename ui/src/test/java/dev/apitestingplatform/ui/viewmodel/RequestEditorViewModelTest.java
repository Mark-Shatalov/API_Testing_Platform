package dev.apitestingplatform.ui.viewmodel;

import dev.apitestingplatform.application.http.RequestValidation;
import dev.apitestingplatform.domain.http.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RequestEditorViewModelTest {
    @Test void loadCaptureRoundTripsInvalidDraftAndEveryRapidChangeAdvancesRevision() {
        MainWindowViewModel main = new MainWindowViewModel(); var draft = new RequestEditorViewModel(main);
        main.requestUrlProperty().set("unfinished url"); draft.bodyText.set("{broken}"); draft.timeout.set("invalid");
        draft.bodyType.set(RequestBody.Type.JSON); draft.form.add(new RequestEditorViewModel.Row("draft", "form", true));
        var part = new RequestEditorViewModel.Row("file", "", false); part.file.set(true); draft.multipart.add(part);
        var captured = draft.capture(); var loaded = new RequestEditorViewModel(new MainWindowViewModel());
        loaded.load(captured); assertThat(loaded.capture()).isEqualTo(captured); assertThat(loaded.dirtyProperty().get()).isFalse();
        long before = loaded.revision();
        for (int i = 0; i < 40; i++) loaded.bodyText.set("Edit " + i);
        assertThat(loaded.revision()).isEqualTo(before + 40); assertThat(loaded.capture().bodyText()).isEqualTo("Edit 39");
        loaded.markSaved(before); assertThat(loaded.dirtyProperty().get()).isTrue();
        loaded.markSaved(loaded.revision()); assertThat(loaded.dirtyProperty().get()).isFalse();
    }
    @Test void snapshotsAreImmutableAndSendingDoesNotResetDrafts() {
        MainWindowViewModel main = new MainWindowViewModel();
        RequestEditorViewModel draft = new RequestEditorViewModel(main);
        assertThat(draft.dirtyProperty().get()).isFalse();
        main.requestUrlProperty().set("http://localhost/test");
        main.requestMethodProperty().set(HttpMethodOption.POST);
        draft.params.add(new RequestEditorViewModel.Row("q", "old", true));
        draft.bodyType.set(RequestBody.Type.JSON); draft.bodyText.set("{\"x\":1}");
        var snapshot = draft.snapshot().orElseThrow();
        draft.params.getFirst().value.set("new"); draft.bodyText.set("unsent edit");
        assertThat(snapshot.queryParameters().getFirst().value()).isEqualTo("old");
        assertThat(snapshot.body().content()).isEqualTo("{\"x\":1}");
        assertThat(draft.bodyText.get()).isEqualTo("unsent edit");
        assertThat(draft.dirtyProperty().get()).isTrue();
        draft.bodyType.set(RequestBody.Type.NONE);
        assertThat(draft.snapshot().orElseThrow().body().type()).isEqualTo(RequestBody.Type.NONE);
        assertThat(draft.bodyText.get()).isEqualTo("unsent edit");
    }

    @Test void timeoutAndFieldErrorsAreSpecificAndDoNotDestroyUserInput() {
        var draft = new RequestEditorViewModel(new MainWindowViewModel());
        for (String invalid : List.of("0", "-1", "NaN", "86401", "0.0000000001")) {
            draft.timeout.set(invalid);
            assertThat(draft.snapshot()).isEmpty();
            assertThat(draft.timeoutError.get()).contains("Timeout");
            assertThat(draft.timeout.get()).isEqualTo(invalid);
        }
        draft.timeout.set("0.125");
        assertThat(draft.snapshot().orElseThrow().timeout().toMillis()).isEqualTo(125);
        draft.headers.add(new RequestEditorViewModel.Row("Host", "secret", true));
        draft.showIssues(List.of(new RequestValidation.Issue("headers.0", "Managed header"),
                new RequestValidation.Issue("url", "Invalid URL")));
        assertThat(draft.headers.getFirst().error.get()).isEqualTo("Managed header");
        assertThat(draft.urlError.get()).isEqualTo("Invalid URL");
        assertThat(draft.headers.getFirst().value.get()).isEqualTo("secret");
        assertThat(draft.summary.get()).contains("Request not sent", "headers.0", "url");
    }
}
