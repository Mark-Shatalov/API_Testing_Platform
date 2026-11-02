package dev.apitestingplatform.persistence.sqlite;

import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class EnvironmentRepositoryTest {
    @TempDir Path directory;
    private final List<SqliteLibraryRepository> opened = new ArrayList<>();
    // Deterministic test adapter; production DPAPI is exercised by the app/security tests.
    private final PayloadProtector protector = new PayloadProtector() {
        public byte[] protect(byte[] input) { byte[] output = input.clone(); for (int i = 0; i < output.length; i++) output[i] ^= 0x5a; return output; }
        public byte[] unprotect(byte[] input) { return protect(input); }
    };
    private static <T> T await(CompletionStage<T> action) throws Exception { return action.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    private SqliteLibraryRepository open() throws Exception {
        var repository = new SqliteLibraryRepository(directory.resolve("platform.sqlite"), protector); opened.add(repository); await(repository.ready()); return repository;
    }
    @AfterEach void cleanup() throws Exception { for (var repository : opened) await(repository.shutdown()); }
    private UUID workspace(SqliteLibraryRepository repository) throws Exception { return await(repository.create(LibraryItem.Kind.WORKSPACE, null, "Workspace")).id(); }

    @Test void crudSelectionOrderingAndEncryptedValuesSurviveRestart() throws Exception {
        var repository = open(); UUID workspace = workspace(repository);
        await(repository.saveWorkspaceVariables(workspace, 0, List.of(new Variable("host", "workspace-private-marker", true))));
        var environment = await(repository.createEnvironment(workspace, "Test", List.of(new Variable("token", "env-private-marker", true), new Variable("id", "2", false))));
        await(repository.selectEnvironment(workspace, environment.id()));
        var collection = await(repository.create(LibraryItem.Kind.COLLECTION, workspace, "Collection"));
        var empty = RequestDraft.empty();
        var local = new RequestDraft(empty.method(), empty.url(), empty.params(), empty.headers(), empty.cookies(), empty.bodyType(), empty.bodyText(),
                empty.form(), empty.multipart(), empty.timeout(), empty.redirects(), empty.protocol(), List.of(new Variable("local", "request-private-marker", true)));
        var request = await(repository.createRequest(collection.id(), "Local variables", local));
        var changed = await(repository.saveEnvironment(new Environment(environment.id(), workspace, "Renamed", environment.version(), environment.modifiedAt(),
                List.of(new Variable("id", "3", false), new Variable("token", "env-private-marker", true)))));
        assertThat(changed.version()).isEqualTo(1); assertThat(changed.variables()).extracting(Variable::name).containsExactly("id", "token");
        await(repository.shutdown());
        var restarted = open(); var loaded = await(restarted.loadVariables(workspace));
        assertThat(loaded.selectedEnvironmentId()).isEqualTo(environment.id()); assertThat(loaded.variables().getFirst().value()).isEqualTo("workspace-private-marker");
        assertThat(loaded.selectedVariables()).extracting(Variable::value).containsExactly("3", "env-private-marker");
        assertThat(await(restarted.load(request.item().id())).draft()).isEqualTo(local);
        await(restarted.deleteEnvironment(changed.id(), changed.version()));
        assertThat(await(restarted.loadVariables(workspace)).selectedEnvironmentId()).isNull();
        await(restarted.shutdown());
        for (String name : List.of("platform.sqlite", "platform.sqlite-wal")) {
            Path file = directory.resolve(name);
            if (Files.exists(file)) assertThat(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1))
                    .doesNotContain("workspace-private-marker", "env-private-marker", "request-private-marker");
        }
    }
    @Test void staleWritesDuplicateNamesAndCrossWorkspaceSelectionAreRejected() throws Exception {
        var repository = open(); UUID workspace = workspace(repository), other = workspace(repository);
        var environment = await(repository.createEnvironment(workspace, "Test", List.of()));
        await(repository.saveEnvironment(new Environment(environment.id(), workspace, "New", 0, environment.modifiedAt(), List.of())));
        assertThatThrownBy(() -> await(repository.saveEnvironment(environment))).hasCauseInstanceOf(StorageException.class);
        assertThatThrownBy(() -> await(repository.deleteEnvironment(environment.id(), 0))).hasCauseInstanceOf(StorageException.class);
        assertThatThrownBy(() -> await(repository.createEnvironment(workspace, "new", List.of()))).hasCauseInstanceOf(StorageException.class);
        assertThatThrownBy(() -> await(repository.selectEnvironment(other, environment.id()))).hasCauseInstanceOf(StorageException.class);
        await(repository.saveWorkspaceVariables(workspace, 0, List.of(new Variable("id", "1", false))));
        assertThatThrownBy(() -> await(repository.saveWorkspaceVariables(workspace, 0, List.of()))).hasCauseInstanceOf(StorageException.class);
        assertThat(await(repository.loadVariables(workspace)).variables()).hasSize(1);
        assertThat(await(repository.loadVariables(other)).environments()).isEmpty();
        await(repository.delete(workspace));
        assertThatThrownBy(() -> await(repository.loadVariables(workspace))).hasCauseInstanceOf(StorageException.class);
    }
    @Test void upgradesV1WithoutChangingExistingRequestOrRecoveryPayloads() throws Exception {
        Path file = directory.resolve("platform.sqlite");
        var old = new SqliteLibraryRepository(file, protector, List.of(Migrations.defaults().getFirst())); opened.add(old); await(old.ready());
        UUID workspace = workspace(old); var collection = await(old.create(LibraryItem.Kind.COLLECTION, workspace, "Collection"));
        var request = await(old.createRequest(collection.id(), "Saved", RequestDraft.empty())); await(old.shutdown());
        // Simulate an actual old JSON payload whose request-local variables field does not exist.
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var tree = json.valueToTree(RequestDraft.empty()); ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("variables");
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file); var update = db.prepareStatement("UPDATE requests SET payload_json=? WHERE id=?")) {
            update.setString(1, new ProtectedDraftCodec(protector).encodeValue(tree)); update.setString(2, request.item().id().toString()); update.executeUpdate();
        }
        var upgraded = open(); assertThat(await(upgraded.load(request.item().id())).draft()).isEqualTo(RequestDraft.empty());
        assertThat(await(upgraded.loadVariables(workspace)).variables()).isEmpty();
        assertThat(await(upgraded.createEnvironment(workspace, "After upgrade", List.of())).name()).isEqualTo("After upgrade");
    }
}
