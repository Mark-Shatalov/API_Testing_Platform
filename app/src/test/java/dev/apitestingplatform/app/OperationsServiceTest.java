package dev.apitestingplatform.app;

import dev.apitestingplatform.application.operations.EventLog;
import dev.apitestingplatform.domain.operations.OperationalSettings;
import dev.apitestingplatform.domain.operations.OperationalSettings.ProxyMode;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class OperationsServiceTest {
    @TempDir Path directory;
    static <T> T await(CompletionStage<T> operation) throws Exception { return operation.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    @Test void preferencesRoundTripAndCorruptionUsesSafeDefaultsWithoutOverwritingOriginal() throws Exception {
        var file = directory.resolve("operations.properties"); var preferences = new OperationalPreferences(file);
        assertThat(preferences.load().settings()).isEqualTo(OperationalSettings.defaults());
        var settings = new OperationalSettings(ProxyMode.MANUAL, "localhost", 9999, "", 256); preferences.save(settings);
        assertThat(new OperationalPreferences(file).load().settings()).isEqualTo(settings);
        Files.writeString(file, "version=999\nsecret=original-marker");
        assertThat(preferences.load().settings()).isEqualTo(OperationalSettings.defaults()); assertThat(preferences.load().notice()).contains("Safe defaults");
        assertThat(Files.readString(file)).contains("original-marker");
    }
    @Test void settingsValidationFailureKeepsPreviousPreferences() throws Exception {
        try (var fixture = new Fixture()) {
            await(fixture.service.saveSettings(new OperationalSettings(ProxyMode.SYSTEM, "", 8080, "", 128)));
            Path invalid = directory.resolve("private.pem"); Files.writeString(invalid, "-----BEGIN PRIVATE KEY----- secret-marker");
            assertThatThrownBy(() -> await(fixture.service.saveSettings(new OperationalSettings(ProxyMode.DIRECT, "", 8080, invalid.toString(), 64)))).hasCauseInstanceOf(java.io.IOException.class);
            assertThat(await(fixture.service.info()).settings().proxyMode()).isEqualTo(ProxyMode.SYSTEM);
        }
    }
    @Test void diagnosticArchiveWhitelistsEventsAndSummaryAndCannotOverwriteManagedData() throws Exception {
        try (var fixture = new Fixture()) {
            await(fixture.service.saveSettings(new OperationalSettings(ProxyMode.MANUAL, "private-proxy-marker", 9123, "", 256)));
            Files.createDirectories(fixture.log.directory()); Files.writeString(fixture.log.directory().resolve("operations.log"), "Authorization: Bearer secret-marker\n");
            Files.writeString(fixture.log.directory().resolve("other.log"), "secret-other-file-marker");
            fixture.log.record(EventLog.Level.INFO, EventLog.Component.STARTUP, EventLog.Code.APP_STARTED, UUID.randomUUID());
            Path archive = directory.resolve("diagnostic.zip"); await(fixture.service.diagnostics(archive));
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                assertThat(zip.stream().map(ZipEntry::getName).toList()).containsExactly("diagnostics.properties", "events.log");
                String text = new String(zip.getInputStream(zip.getEntry("diagnostics.properties")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) + new String(zip.getInputStream(zip.getEntry("events.log")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertThat(text).contains("APP_STARTED", "responseLimitKiB=256").doesNotContain("private-proxy-marker", "secret-marker", "secret-other-file-marker", directory.toString(), "platform.sqlite");
            }
            assertThatThrownBy(() -> await(fixture.service.diagnostics(fixture.repository.databasePath()))).hasCauseInstanceOf(java.io.IOException.class);
            assertThat(await(fixture.repository.list())).isEmpty();
        }
    }
    @Test void actualHttpLogsOnlyNumericOutcomeWithSameCorrelationId() throws Exception {
        try (var log = new OperationalLog(directory.resolve("logs")); var server = new MockWebServer(); var engine = new LoggedHttpEngine(new JavaHttpExecutionEngine(), log)) {
            server.enqueue(new MockResponse().setBody("private-response-marker").addHeader("Set-Cookie", "private-cookie-marker"));
            var result = await(engine.execute(RequestConfiguration.get(server.url("/private-path-marker?token=private-token-marker").toString())).result()); assertThat(result.statusCode()).isEqualTo(200);
            String events = new String(await(log.snapshot()), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(events).contains("REQUEST_STARTED", "REQUEST_FINISHED", "HTTP_STATUS=200").doesNotContain("private-", "127.0.0.1", "localhost");
            var lines = events.strip().split("\n"); assertThat(lines).hasSize(2); assertThat(lines[0].split("\\|")[3]).isEqualTo(lines[1].split("\\|")[3]);
        }
    }
    @Test void rotatingLogIsBoundedAndUnwritableLogDoesNotBreakOperations() throws Exception {
        Path logs = directory.resolve("logs"); Files.createDirectories(logs);
        try (var log = new OperationalLog(logs)) {
            for (int index = 0; index < 5; index++) {
                Files.writeString(logs.resolve("operations.log"), "x".repeat(512 * 1024));
                log.record(EventLog.Level.INFO, EventLog.Component.BACKUP, EventLog.Code.BACKUP_CREATED, UUID.randomUUID()); await(log.snapshot());
            }
            try (var files = Files.list(logs)) { assertThat(files.toList()).hasSize(4).allSatisfy(file -> assertThat(Files.size(file)).isLessThanOrEqualTo(512 * 1024)); }
        }
        Path denied = directory.resolve("not-a-directory"); Files.writeString(denied, "keep");
        try (var log = new OperationalLog(denied)) { log.record(EventLog.Level.ERROR, EventLog.Component.UI, EventLog.Code.UNEXPECTED_ERROR, UUID.randomUUID()); assertThat(await(log.snapshot())).isEmpty(); assertThat(log.notice()).contains("not writable"); }
    }
    @Test void diagnosticsAndRestoreControlsRemainAvailableWhenDatabaseCannotStart() throws Exception {
        Path file = directory.resolve("platform.sqlite"); Files.writeString(file, "corrupt input");
        try (var repository = new SqliteLibraryRepository(file, new DpapiPayloadProtector()); var log = new OperationalLog(directory.resolve("logs")); var service = new OperationsService(repository, new OperationalPreferences(directory.resolve("operations.properties")), log, "")) {
            assertThatThrownBy(() -> await(repository.ready())).hasCauseInstanceOf(dev.apitestingplatform.application.storage.StorageException.class);
            assertThat(await(service.info()).startupNotice()).contains("could not start");
            await(service.diagnostics(directory.resolve("failure.zip"))); assertThat(directory.resolve("failure.zip")).exists(); await(service.cancelRestore());
            assertThat(Files.readString(file)).isEqualTo("corrupt input"); await(repository.shutdown());
        }
    }
    @Test void diagnosticDestinationStillWorksBeforeTheDataDirectoryCanBeCreated() throws Exception {
        Path output = directory.resolve("diagnostic.zip");
        assertThat(dev.apitestingplatform.application.operations.ManagedFiles.output(directory.resolve("missing/platform.sqlite"), output, false)).isEqualTo(output);
        assertThat(directory.resolve("missing")).doesNotExist();
    }
    final class Fixture implements AutoCloseable {
        final SqliteLibraryRepository repository = new SqliteLibraryRepository(directory.resolve("platform.sqlite"), new DpapiPayloadProtector());
        final OperationalLog log = new OperationalLog(directory.resolve("logs"));
        final OperationsService service = new OperationsService(repository, new OperationalPreferences(directory.resolve("operations.properties")), log, "");
        Fixture() throws Exception { await(repository.ready()); }
        public void close() throws Exception { service.close(); await(log.snapshot()); log.close(); await(repository.shutdown()); }
    }
}
