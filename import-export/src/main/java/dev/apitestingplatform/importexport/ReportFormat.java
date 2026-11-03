package dev.apitestingplatform.importexport;

import dev.apitestingplatform.application.storage.HistorySanitizer;
import dev.apitestingplatform.domain.run.HistoryRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** Standalone non-secret report, with no scripts, links to local resources or embedded bodies. */
public final class ReportFormat {
    public static final int LIMIT = 8 * 1024 * 1024;
    private ReportFormat() { }
    public static byte[] json(HistoryRecord record) throws IOException { return PortableWorkspaceFormat.boundedWrite(Map.of("format", "api-testing-platform-report", "version", 1, "report", HistorySanitizer.clean(record)), LIMIT); }
    public static byte[] html(HistoryRecord record) throws IOException {
        var safe = HistorySanitizer.clean(record);
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>API Testing Platform report</title><style>body{font:15px system-ui;margin:32px;color:#20242a}table{border-collapse:collapse;width:100%}th,td{padding:9px;border:1px solid #ccc;text-align:left}h1{font-size:24px}details{margin:12px 0}pre{white-space:pre-wrap}small{color:#555}</style><h1>API Testing Platform report</h1>");
        html.append("<p>").append(escape(safe.title())).append(" · ").append(safe.state()).append(" · ").append(Instant.ofEpochMilli(safe.startedMillis())).append("</p><p>")
                .append(safe.steps().size()).append('/').append(safe.planned()).append(" recorded steps · ").append(safe.requestFailures()).append(" request failures · ").append(safe.failedChecks()).append(" failed checks · ").append(safe.extractionProblems()).append(" extraction problems</p><small>Headers, bodies, credentials and compared response values are omitted.</small><table><tr><th>#</th><th>Iteration</th><th>Request</th><th>Outcome</th><th>HTTP</th><th>Time (ms)</th><th>Bytes</th><th>Checks (pass/fail/not run)</th></tr>");
        for (var step : safe.steps()) { var entry = step.entry(); html.append("<tr><td>").append(entry.position()).append("</td><td>").append(entry.iteration()).append("</td><td>").append(escape(entry.name())).append("</td><td>").append(entry.outcome())
                .append("</td><td>").append(entry.statusCode()).append("</td><td>").append(entry.durationMillis()).append("</td><td>").append(entry.sizeBytes()).append("</td><td>").append(step.passed()).append('/').append(step.failed()).append('/').append(step.notRun()).append("</td></tr>"); }
        html.append("</table>");
        for (var step : safe.steps()) { var entry = step.entry(); html.append("<details><summary>").append(entry.position()).append(". ").append(escape(entry.name())).append("</summary><p>").append(escape(entry.details())).append("</p>");
            for (var check : entry.assertions()) html.append("<p>Check: ").append(escape(check.name())).append(" — ").append(check.outcome()).append(" · ").append(escape(check.reason())).append("</p>");
            for (var extraction : entry.extractions()) html.append("<p>Extraction: ").append(escape(extraction.variable())).append(" — ").append(extraction.outcome()).append(" · ").append(escape(extraction.details())).append("</p>");
            if (step.detailsOmitted()) html.append("<p>Some rule details were omitted by the report size limit; counts remain complete.</p>"); html.append("</details>");
        }
        html.append("</html>"); byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8); if (bytes.length > LIMIT) throw new IOException("Report exceeds the 8 MiB export limit."); return bytes;
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
}
