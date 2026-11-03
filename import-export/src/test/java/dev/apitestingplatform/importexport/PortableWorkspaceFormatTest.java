package dev.apitestingplatform.importexport;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.domain.variables.Variable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PortableWorkspaceFormatTest {
    @TempDir Path directory;
    static WorkspaceTransfer sample() {
        UUID workspace = UUID.randomUUID(), collection = UUID.randomUUID(), folder = UUID.randomUUID(), request = UUID.randomUUID(), environment = UUID.randomUUID();
        var auth = new Authentication(Authentication.Mode.BEARER, "bearer-private-marker", "user-private-marker", "password-private-marker", "X-Key", "key-private-marker", Authentication.Placement.HEADER);
        var draft = new RequestDraft(HttpMethod.POST, "https://uri-user:uri-private-password@example.test/{{path}}?access_token=uri-private-token&ordinary=query-private-value#fragment-private",
                List.of(new RequestField("q", "param-private-marker")), List.of(new RequestField("X-Custom", "header-private-marker")), List.of(new RequestField("sid", "cookie-private-marker")), RequestBody.Type.JSON, "{\"secret\":\"body-private-marker\"}",
                List.of(new RequestField("data", "form-private-marker")), List.of(new RequestDraft.Part("upload", "C:\\private-path\\secret.bin", true, "application/octet-stream", true)), "30", RedirectPolicy.NORMAL, HttpProtocol.HTTP_2,
                List.of(new Variable("path", "path-private-marker", true), new Variable("publicCopy", "uri-private-token", false)), auth,
                List.of(new AssertionDefinition(UUID.randomUUID(), "Status", AssertionDefinition.Type.STATUS_EQUALS, "", "200", true), new AssertionDefinition(UUID.randomUUID(), "Body equality", AssertionDefinition.Type.JSON_PATH_EQUALS, "$.secret", "\"expected-private-marker\"", true)),
                List.of(new ExtractionDefinition(UUID.randomUUID(), "token", "$.access_token", true)));
        return new WorkspaceTransfer(workspace, List.of(new WorkspaceTransfer.Node(workspace, LibraryItem.Kind.WORKSPACE, null, "Workspace", 0, null),
                new WorkspaceTransfer.Node(collection, LibraryItem.Kind.COLLECTION, workspace, "Collection", 0, null), new WorkspaceTransfer.Node(folder, LibraryItem.Kind.FOLDER, collection, "Folder", 0, null),
                new WorkspaceTransfer.Node(request, LibraryItem.Kind.REQUEST, folder, "Request", 0, draft)),
                List.of(new Variable("baseUrl", "https://example.test/", false), new Variable("password", "workspace-private-marker", false)),
                List.of(new WorkspaceTransfer.TransferEnvironment(environment, workspace, "Local", List.of(new Variable("token", "environment-private-marker", true), new Variable("id", "42", false)))), environment);
    }
    @Test void fullOptInRoundTripPreservesHierarchyRulesInactiveDraftsAndEnvironmentReferences() throws Exception {
        var original = sample(); var document = PortableWorkspaceFormat.read(PortableWorkspaceFormat.write(original, true));
        assertThat(document.secretsIncluded()).isTrue(); assertThat(document.workspace()).isEqualTo(original);
        assertThat(document.toString()).doesNotContain("bearer-private-marker");
    }
    @Test void defaultExportOmitsCredentialsUnmarkedSensitiveNamesBodiesFilesCookiesAndComparedValues() throws Exception {
        byte[] bytes = PortableWorkspaceFormat.write(sample(), false); String json = new String(bytes, StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("bearer-private-marker", "user-private-marker", "password-private-marker", "key-private-marker", "uri-private-password", "uri-private-token", "query-private-value", "fragment-private", "param-private-marker", "header-private-marker", "cookie-private-marker", "body-private-marker", "form-private-marker", "private-path", "expected-private-marker", "path-private-marker", "workspace-private-marker", "environment-private-marker");
        var document = PortableWorkspaceFormat.read(bytes); assertThat(document.secretsIncluded()).isFalse();
        assertThat(document.workspace().variables().getFirst().value()).isEqualTo("https://example.test/");
        assertThat(document.workspace().environments().getFirst().variables().get(1).value()).isEqualTo("42");
        var request = document.workspace().nodes().getLast().request();
        assertThat(request.bodyText()).isEqualTo("{{REDACTED}}"); assertThat(request.multipart().getFirst().value()).isEmpty();
        assertThat(request.assertions().getFirst().expected()).isEqualTo("200"); assertThat(request.extractions().getFirst().path()).isEqualTo("$.access_token");
    }
    @ParameterizedTest @ValueSource(strings = {"version", "format", "unknown", "nested-unknown", "duplicate-id", "missing-root", "missing-parent", "nested-folder", "position", "string-position", "null-position", "selected-environment", "duplicate-environment", "numeric-kind", "numeric-name", "numeric-enabled", "string-secretflag", "fractional-version"})
    void rejectsMalformedReferencesTypesVersionsAndUnsupportedFields(String mutation) throws Exception {
        ObjectMapper json = new ObjectMapper(); ObjectNode root = (ObjectNode) json.readTree(PortableWorkspaceFormat.write(sample(), true));
        ObjectNode workspace = (ObjectNode) root.get("workspace"); ArrayNode nodes = (ArrayNode) workspace.get("nodes");
        switch (mutation) {
            case "version" -> root.put("version", 99);
            case "numeric-kind" -> ((ObjectNode) nodes.get(0)).put("kind", 0);
            case "numeric-name" -> ((ObjectNode) nodes.get(0)).put("name", 123);
            case "numeric-enabled" -> ((ObjectNode) nodes.get(3).get("request").get("params").get(0)).put("enabled", 1);
            case "string-secretflag" -> root.put("secretsIncluded", "true");
            case "fractional-version" -> root.put("version", 1.5);
            case "format" -> root.put("format", "postman");
            case "unknown" -> root.put("unsupported", "private-input-marker");
            case "nested-unknown" -> ((ObjectNode) nodes.get(3).get("request")).put("script", "alert('private-input-marker')");
            case "duplicate-id" -> ((ObjectNode) nodes.get(3)).put("id", nodes.get(1).get("id").asText());
            case "missing-root" -> workspace.put("workspaceId", UUID.randomUUID().toString());
            case "missing-parent" -> ((ObjectNode) nodes.get(3)).put("parentId", UUID.randomUUID().toString());
            case "nested-folder" -> ((ObjectNode) nodes.get(2)).put("parentId", nodes.get(3).get("id").asText());
            case "position" -> ((ObjectNode) nodes.get(3)).put("position", 5);
            case "string-position" -> ((ObjectNode) nodes.get(3)).put("position", "0");
            case "null-position" -> ((ObjectNode) nodes.get(3)).putNull("position");
            case "selected-environment" -> workspace.put("selectedEnvironmentId", UUID.randomUUID().toString());
            case "duplicate-environment" -> ((ArrayNode) workspace.get("environments")).add(workspace.get("environments").get(0).deepCopy());
        }
        assertThatThrownBy(() -> PortableWorkspaceFormat.read(json.writeValueAsBytes(root))).isInstanceOf(IOException.class).hasMessageNotContaining("private-input-marker");
    }
    @Test void rejectsDuplicateJsonPropertiesDeepInputAndOversizedFiles() throws Exception {
        String valid = new String(PortableWorkspaceFormat.write(sample(), true), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> PortableWorkspaceFormat.read(valid.replace("\"version\" : 1", "\"version\" : 1, \"version\" : 1").getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> PortableWorkspaceFormat.read(("[".repeat(101) + "0" + "]".repeat(101)).getBytes(StandardCharsets.UTF_8))).isInstanceOf(IOException.class);
        Path oversized = directory.resolve("oversized.json"); Files.write(oversized, new byte[PortableWorkspaceFormat.LIMIT + 1]);
        try (var files = new TransferFiles()) { assertThatThrownBy(() -> files.read(oversized).toCompletableFuture().get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class); }
    }
    @ParameterizedTest @ValueSource(strings = {"bearer", "basic"})
    void manualAuthorizationHeaderSecretsAreRemovedFromOtherFieldsToo(String mode) throws Exception {
        var original = sample(); var old = original.nodes().getLast(); var empty = RequestDraft.empty(); String token = "manual-private-token";
        String header = mode.equals("bearer") ? "Bearer " + token : "Basic " + Base64.getEncoder().encodeToString(("user:" + token).getBytes(StandardCharsets.UTF_8));
        var request = new RequestDraft(empty.method(), "https://example.test/" + token, List.of(), List.of(new RequestField("Authorization", header)), List.of(), empty.bodyType(), "", List.of(), List.of(), empty.timeout(), empty.redirects(), empty.protocol(), List.of(new Variable("copy", token, false)));
        List<WorkspaceTransfer.Node> nodes = new ArrayList<>(original.nodes()); nodes.set(nodes.size() - 1, new WorkspaceTransfer.Node(old.id(), old.kind(), old.parentId(), old.name(), old.position(), request));
        var workspace = new WorkspaceTransfer(original.workspaceId(), nodes, original.variables(), original.environments(), original.selectedEnvironmentId());
        assertThat(new String(PortableWorkspaceFormat.write(workspace, false), StandardCharsets.UTF_8)).doesNotContain(token, header);
    }
    @Test void oversizedExportLeavesExistingDestinationUntouched() throws Exception {
        var original = sample(); var template = original.nodes().getLast(); List<WorkspaceTransfer.Node> nodes = new ArrayList<>(original.nodes().subList(0, 3));
        String body = "x".repeat(8 * 1024 * 1024); var empty = RequestDraft.empty();
        for (int index = 0; index < 4; index++) {
            var request = new RequestDraft(empty.method(), "https://example.test/", List.of(), List.of(), List.of(), RequestBody.Type.TEXT, body, List.of(), List.of(), empty.timeout(), empty.redirects(), empty.protocol());
            nodes.add(new WorkspaceTransfer.Node(UUID.randomUUID(), LibraryItem.Kind.REQUEST, template.parentId(), "Large " + index, index, request));
        }
        var workspace = new WorkspaceTransfer(original.workspaceId(), nodes, original.variables(), original.environments(), original.selectedEnvironmentId());
        Path target = directory.resolve("keep.json"); Files.writeString(target, "previous export");
        try (var files = new TransferFiles()) { assertThatThrownBy(() -> files.workspace(target, workspace, true).toCompletableFuture().get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class); }
        assertThat(Files.readString(target)).isEqualTo("previous export");
    }
    @Test void fileWorkerReplacesAtomicallyAndRemovesTemporaryFilesOnFailure() throws Exception {
        Path target = directory.resolve("workspace.json"); Files.writeString(target, "previous");
        try (var files = new TransferFiles()) {
            files.workspace(target, sample(), true).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(files.read(target).toCompletableFuture().get(5, TimeUnit.SECONDS).workspace().nodes()).hasSize(4);
            Path blocked = Files.createDirectory(directory.resolve("directory-target"));
            assertThatThrownBy(() -> files.workspace(blocked, sample(), true).toCompletableFuture().get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IOException.class);
            try (var paths = Files.list(directory)) { assertThat(paths.map(path -> path.getFileName().toString()).toList()).noneMatch(name -> name.startsWith(".api-export-")); }
            assertThat(Files.isDirectory(blocked)).isTrue();
        }
    }
}
