package dev.apitestingplatform.domain.storage;

import dev.apitestingplatform.domain.http.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StorageModelsTest {
    @Test void validatesNamesIdentityAndHierarchyRootContracts() {
        UUID id = UUID.randomUUID();
        var item = new LibraryItem(id, LibraryItem.Kind.WORKSPACE, null, "  Workspace  ", 0, 0, Instant.now());
        assertThat(item.name()).isEqualTo("Workspace"); assertThat(item.id()).isEqualTo(id);
        for (String name : List.of("", "  ", "x\nname", "x".repeat(201))) {
            assertThatThrownBy(() -> new LibraryItem(id, LibraryItem.Kind.WORKSPACE, null, name, 0, 0, Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new LibraryItem(id, LibraryItem.Kind.REQUEST, null, "Request", 0, 0, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void editableSnapshotsAreImmutableButAllowIncompleteUserInput() {
        List<RequestField> fields = new ArrayList<>(List.of(new RequestField("", "unfinished", false)));
        var draft = new RequestDraft(HttpMethod.POST, "not a URL yet", fields, List.of(), List.of(), RequestBody.Type.JSON,
                "{broken}", List.of(), List.of(), "invalid", RedirectPolicy.NEVER, HttpProtocol.HTTP_2);
        fields.clear(); assertThat(draft.params()).hasSize(1);
        assertThatThrownBy(() -> draft.params().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(draft.url()).isEqualTo("not a URL yet"); assertThat(draft.timeout()).isEqualTo("invalid");
    }
}
