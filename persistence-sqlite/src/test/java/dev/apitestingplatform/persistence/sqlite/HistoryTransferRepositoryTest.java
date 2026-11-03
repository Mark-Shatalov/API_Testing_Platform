package dev.apitestingplatform.persistence.sqlite;

import dev.apitestingplatform.application.storage.*;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.run.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class HistoryTransferRepositoryTest {
    @TempDir Path directory;
    final PayloadProtector delegate = SqliteLibraryRepositoryTest.encryptedTestProtector();
    final AtomicBoolean encryptionFails = new AtomicBoolean();
    final PayloadProtector protector = new PayloadProtector() {
        public byte[] protect(byte[] plain) { if (encryptionFails.get()) throw new IllegalStateException("Protection failed"); return delegate.protect(plain); }
        public byte[] unprotect(byte[] protectedBytes) { return delegate.unprotect(protectedBytes); }
    };
    final List<SqliteLibraryRepository> opened = new ArrayList<>();
    static <T> T await(CompletionStage<T> action) throws Exception { return action.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    Path file() { return directory.resolve("platform.sqlite"); }
    SqliteLibraryRepository open() throws Exception { var repo = new SqliteLibraryRepository(file(), protector); opened.add(repo); await(repo.ready()); return repo; }
    @AfterEach void close() throws Exception { for (var repo : opened) await(repo.shutdown()); }
    WorkspaceTransfer source(SqliteLibraryRepository repo) throws Exception {
        var workspace = await(repo.create(LibraryItem.Kind.WORKSPACE, null, "Source")); var collection = await(repo.create(LibraryItem.Kind.COLLECTION, workspace.id(), "Collection")); var folder = await(repo.create(LibraryItem.Kind.FOLDER, collection.id(), "Folder"));
        var empty = RequestDraft.empty(); var draft = new RequestDraft(empty.method(), "https://example.test/{{id}}", empty.params(), empty.headers(), empty.cookies(), RequestBody.Type.JSON, "{\"data\":\"private-request-data\"}", empty.form(), empty.multipart(), empty.timeout(), empty.redirects(), empty.protocol(),
                List.of(new Variable("local", "private-local-data", true)), Authentication.none(), List.of(new AssertionDefinition(UUID.randomUUID(), "Status", AssertionDefinition.Type.STATUS_EQUALS, "", "200", true)), List.of(new ExtractionDefinition(UUID.randomUUID(), "token", "$.token", true)));
        await(repo.createRequest(folder.id(), "First", draft)); await(repo.createRequest(collection.id(), "Fail item", empty));
        await(repo.saveWorkspaceVariables(workspace.id(), 0, List.of(new Variable("base", "private-workspace-data", true))));
        var env = await(repo.createEnvironment(workspace.id(), "Selected", List.of(new Variable("id", "42", false), new Variable("token", "private-environment-data", true)))); await(repo.selectEnvironment(workspace.id(), env.id()));
        return await(repo.exportWorkspace(workspace.id()));
    }
    @Test void copiedWorkspaceRemapsAllReferencesPreservesOrderAndSurvivesRestartWithoutPlaintextPayloads() throws Exception {
        var repo = open(); var original = source(repo); var before = await(repo.list()); UUID imported = await(repo.importWorkspace(original));
        var copied = await(repo.exportWorkspace(imported));
        assertThat(copied.nodes()).hasSize(original.nodes().size()); assertThat(copied.nodes().stream().map(WorkspaceTransfer.Node::id).toList()).doesNotContainAnyElementsOf(original.nodes().stream().map(WorkspaceTransfer.Node::id).toList());
        assertThat(copied.environments().getFirst().id()).isNotEqualTo(original.environments().getFirst().id()); assertThat(copied.selectedEnvironmentId()).isEqualTo(copied.environments().getFirst().id());
        assertThat(copied.variables()).isEqualTo(original.variables()); assertThat(copied.environments().getFirst().variables()).isEqualTo(original.environments().getFirst().variables());
        assertThat(copied.nodes().stream().filter(node -> node.request() != null).map(WorkspaceTransfer.Node::request).toList()).containsExactlyInAnyOrderElementsOf(original.nodes().stream().filter(node -> node.request() != null).map(WorkspaceTransfer.Node::request).toList());
        assertThat(await(repo.list())).containsAll(before); await(repo.shutdown());
        var restarted = open(); assertThat(await(restarted.exportWorkspace(imported))).isEqualTo(copied); assertThat(await(restarted.exportWorkspace(original.workspaceId()))).isEqualTo(original); await(restarted.shutdown());
        for (String name : List.of("platform.sqlite", "platform.sqlite-wal")) if (Files.exists(directory.resolve(name))) assertThat(new String(Files.readAllBytes(directory.resolve(name)), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("private-request-data", "private-local-data", "private-workspace-data", "private-environment-data");
    }
    @Test void databaseFailureRollsBackEveryImportedRowAndOriginalDataRemainsUsable() throws Exception {
        var repo = open(); var transfer = source(repo); var before = await(repo.list());
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file()); var sql = db.createStatement()) { sql.execute("CREATE TRIGGER reject_import BEFORE INSERT ON items WHEN NEW.name='Fail item' BEGIN SELECT RAISE(ABORT,'Import fixture'); END"); }
        assertThatThrownBy(() -> await(repo.importWorkspace(transfer))).hasCauseInstanceOf(StorageException.class);
        assertThat(await(repo.list())).isEqualTo(before); assertThat(await(repo.exportWorkspace(transfer.workspaceId()))).isEqualTo(transfer);
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file()); var sql = db.createStatement()) { sql.execute("DROP TRIGGER reject_import"); }
        assertThat(await(repo.importWorkspace(transfer))).isNotEqualTo(transfer.workspaceId());
    }
    @Test void encryptionFailureBeforeImportMakesNoChanges() throws Exception {
        var repo = open(); var transfer = source(repo); var before = await(repo.list()); encryptionFails.set(true);
        assertThatThrownBy(() -> await(repo.importWorkspace(transfer))).hasCauseInstanceOf(StorageException.class); encryptionFails.set(false);
        assertThat(await(repo.list())).isEqualTo(before); assertThat(await(repo.importWorkspace(transfer))).isNotNull();
    }
    static HistoryRecord history(UUID id, long started, long revision, HistoryRecord.State state) {
        var entry = new RunEntry(1, 1, UUID.randomUUID(), "Request", RunEntry.Outcome.SUCCESS, 200, 25, 42, List.of(new AssertionResult(UUID.randomUUID(), "Check", AssertionResult.Outcome.FAIL, "private-expected", "private-actual", "private-reason")), List.of(), "private-response-content");
        return HistorySanitizer.create(id, HistoryRecord.Kind.REQUEST, null, null, "Report", started, state == HistoryRecord.State.RUNNING ? 0 : started + 100, state, 2, revision, List.of(entry));
    }
    @Test void historyIsSanitizedVersionedAndInterruptedCheckpointsReopenAfterRestart() throws Exception {
        var repo = open(); UUID id = UUID.randomUUID(); long started = System.currentTimeMillis();
        await(repo.saveHistory(history(id, started, 2, HistoryRecord.State.RUNNING)));
        await(repo.saveHistory(history(id, started, 1, HistoryRecord.State.COMPLETED))); // stale retry cannot replace a newer checkpoint
        assertThat(await(repo.loadHistory(id)).state()).isEqualTo(HistoryRecord.State.RUNNING); await(repo.shutdown());
        var restarted = open(); var loaded = await(restarted.loadHistory(id)); assertThat(loaded.state()).isEqualTo(HistoryRecord.State.INTERRUPTED);
        assertThat(loaded.steps()).hasSize(1); assertThat(loaded.planned()).isEqualTo(2); assertThat(loaded.failedChecks()).isEqualTo(1);
        assertThat(loaded.steps().getFirst().entry().assertions().getFirst().actual()).isEqualTo("[omitted]");
        await(restarted.saveHistory(history(id, started, 3, HistoryRecord.State.COMPLETED))); assertThat(await(restarted.loadHistory(id)).state()).isEqualTo(HistoryRecord.State.INTERRUPTED);
        assertThat(await(restarted.listHistory(100, 0)).getFirst().completed()).isEqualTo(1);
        await(restarted.shutdown()); assertThat(new String(Files.readAllBytes(file()), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("private-expected", "private-actual", "private-reason", "private-response-content");
    }
    @Test void retentionAppliesCountAgePaginationAndClearWhileProtectingActiveEntries() throws Exception {
        var repo = open(); await(repo.saveHistorySettings(new HistorySettings(2, 365))); long now = System.currentTimeMillis();
        UUID one = UUID.randomUUID(), two = UUID.randomUUID(), three = UUID.randomUUID(), running = UUID.randomUUID();
        await(repo.saveHistory(history(one, now - 3000, 1, HistoryRecord.State.COMPLETED))); await(repo.saveHistory(history(two, now - 2000, 1, HistoryRecord.State.COMPLETED))); await(repo.saveHistory(history(three, now - 1000, 1, HistoryRecord.State.COMPLETED)));
        assertThat(await(repo.listHistory(100, 0))).extracting(HistorySummary::id).containsExactly(three, two);
        assertThat(await(repo.listHistory(1, 1))).extracting(HistorySummary::id).containsExactly(two);
        await(repo.saveHistory(history(running, now - TimeUnit.DAYS.toMillis(400), 0, HistoryRecord.State.RUNNING))); await(repo.clearHistory());
        assertThat(await(repo.listHistory(100, 0))).extracting(HistorySummary::id).containsExactly(running);
        await(repo.saveHistory(history(one, now - TimeUnit.DAYS.toMillis(2), 1, HistoryRecord.State.COMPLETED))); await(repo.saveHistorySettings(new HistorySettings(2, 1)));
        assertThat(await(repo.listHistory(100, 0))).hasSize(1); await(repo.shutdown()); var restarted = open();
        assertThat(await(restarted.historySettings())).isEqualTo(new HistorySettings(2, 1)); assertThat(await(restarted.listHistory(100, 0))).isEmpty();
    }
    @Test void v2UpgradeKeepsExistingRequestsAndAddsHistoryWithoutChangingOldMigrations() throws Exception {
        var old = new SqliteLibraryRepository(file(), protector, Migrations.defaults().subList(0, 2)); opened.add(old); await(old.ready());
        var transfer = source(old); await(old.shutdown()); var upgraded = open();
        assertThat(await(upgraded.exportWorkspace(transfer.workspaceId()))).isEqualTo(transfer); assertThat(await(upgraded.historySettings())).isEqualTo(HistorySettings.defaults());
        await(upgraded.saveHistory(history(UUID.randomUUID(), System.currentTimeMillis(), 1, HistoryRecord.State.COMPLETED))); assertThat(await(upgraded.listHistory(100, 0))).hasSize(1);
    }
}
