package dev.apitestingplatform.importexport;

import dev.apitestingplatform.application.storage.HistorySanitizer;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.run.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ReportFormatTest {
    @TempDir Path directory;
    @Test void jsonAndReadableHtmlExcludeComparedValuesAndEscapeUserMarkup() throws Exception {
        var entry = new RunEntry(1, 1, UUID.randomUUID(), "<script>alert('name')</script>", RunEntry.Outcome.NETWORK_ERROR, 0, 42, 128,
                List.of(new AssertionResult(UUID.randomUUID(), "<b>Check</b>", AssertionResult.Outcome.FAIL, "expected-private-marker", "actual-private-marker", "raw-private-message")), List.of(), "raw-network-private-marker");
        var record = HistorySanitizer.create(UUID.randomUUID(), HistoryRecord.Kind.REQUEST, null, null, "Report", System.currentTimeMillis(), System.currentTimeMillis(), HistoryRecord.State.COMPLETED_WITH_FAILURES, 1, 1, List.of(entry));
        String json = new String(ReportFormat.json(record), StandardCharsets.UTF_8), html = new String(ReportFormat.html(record), StandardCharsets.UTF_8);
        assertThat(json + html).doesNotContain("expected-private-marker", "actual-private-marker", "raw-private-message", "raw-network-private-marker");
        assertThat(html).doesNotContain("<script>", "<b>Check</b>").contains("&lt;script&gt;", "&lt;b&gt;Check&lt;/b&gt;", "42", "128");
        assertThat(json).contains("api-testing-platform-report", "version", "[omitted]");
        try (var files = new TransferFiles()) {
            Path path = directory.resolve("report.html"); files.report(path, record, true).toCompletableFuture().get(5, TimeUnit.SECONDS); assertThat(Files.readString(path)).isEqualTo(html);
            files.report(path, record, false).toCompletableFuture().get(5, TimeUnit.SECONDS); assertThat(Files.readString(path)).isEqualTo(json);
        }
    }
}
