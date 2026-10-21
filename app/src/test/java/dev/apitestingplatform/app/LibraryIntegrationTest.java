package dev.apitestingplatform.app;

import dev.apitestingplatform.domain.http.*;
import dev.apitestingplatform.domain.storage.*;
import dev.apitestingplatform.httpclient.JavaHttpExecutionEngine;
import dev.apitestingplatform.persistence.sqlite.SqliteLibraryRepository;
import dev.apitestingplatform.security.windows.DpapiPayloadProtector;
import dev.apitestingplatform.ui.controller.LibraryWindowController;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static dev.apitestingplatform.app.FxTestSupport.*;
import static org.assertj.core.api.Assertions.*;

class LibraryIntegrationTest {
    @TempDir Path directory;
    private final List<Fixture> fixtures = new ArrayList<>();
    @BeforeAll static void toolkit() throws Exception { FxTestSupport.start(); }
    @AfterEach void cleanup() throws Exception { for (var fixture : fixtures) fixture.close(); }
    private static <T> T await(CompletionStage<T> value) throws Exception { return value.toCompletableFuture().get(10, TimeUnit.SECONDS); }
    private Fixture open() throws Exception { var f = new Fixture(directory.resolve("platform.sqlite")); fixtures.add(f); return f; }
    private static RequestDraft draft(String url) {
        return new RequestDraft(HttpMethod.POST, url, List.of(new RequestField("q", "é", false)),
                List.of(new RequestField("X-Client", "desktop")), List.of(new RequestField("session", "secret-cookie")),
                RequestBody.Type.TEXT, "saved body", List.of(new RequestField("form", "retained")),
                List.of(new RequestDraft.Part("file", "C:\\missing.bin", true, "application/octet-stream", false)),
                "3", RedirectPolicy.NEVER, HttpProtocol.HTTP_1_1);
    }

    @Test void createButtonsSelectNewParentsAndCreateFolderAndRequestThroughDialogs() throws Exception {
        Fixture f = open();
        createByButton(f, "#library-new-collection", "Button collection");
        f.awaitTree(1);
        fx(() -> {
            var tree = libraryTree(f);
            assertThat(tree.getSelectionModel().getSelectedItem()).as("The new collection must be selected for the next create action").isNotNull();
            assertThat(tree.getSelectionModel().getSelectedItem().getValue().name()).isEqualTo("Button collection");
            return null;
        });
        createByButton(f, "#library-new-folder", "Button folder");
        waitFor(() -> libraryTree(f).getSelectionModel().getSelectedItem() != null
                && libraryTree(f).getSelectionModel().getSelectedItem().getValue().name().equals("Button folder"));
        createByButton(f, "#library-new-request", "Button request");
        waitFor(() -> libraryTree(f).getSelectionModel().getSelectedItem() != null
                && libraryTree(f).getSelectionModel().getSelectedItem().getValue().name().equals("Button request"));
        var all = await(f.repository.list());
        var collection = all.stream().filter(item -> item.name().equals("Button collection")).findFirst().orElseThrow();
        var folder = all.stream().filter(item -> item.name().equals("Button folder")).findFirst().orElseThrow();
        var request = all.stream().filter(item -> item.name().equals("Button request")).findFirst().orElseThrow();
        assertThat(folder.parentId()).isEqualTo(collection.id()); assertThat(request.parentId()).isEqualTo(folder.id());
        waitFor(() -> f.controller.activeEditor() != null && ((TabPane) f.stage.getScene().getRoot().lookup("#library-request-tabs"))
                .getSelectionModel().getSelectedItem().getText().equals("Button request"));
    }

    @ParameterizedTest @ValueSource(strings = {"folder", "request"})
    void createWithoutTreeSelectionOffersParentChooserAndPersistsChosenParent(String kind) throws Exception {
        Fixture f = open();
        var collection = await(f.repository.create(LibraryItem.Kind.COLLECTION, f.workspace(), "Parent"));
        fx(() -> { f.controller.refresh(); return null; }); f.awaitTree(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        fx(() -> {
            libraryTree(f).getSelectionModel().clearSelection();
            Platform.runLater(() -> {
                DialogPane pane = dialog();
                try {
                    assertThat(pane.lookup(".combo-box")).as("Choose a parent rather than silently returning").isNotNull();
                    ((Button) pane.lookupButton(ButtonType.OK)).fire();
                    Platform.runLater(() -> {
                        DialogPane name = dialog();
                        try {
                            ((TextField) name.lookup(".text-field")).setText("Chosen " + kind);
                            ((Button) name.lookupButton(ButtonType.OK)).fire();
                        } catch (Throwable failure) { failed.set(failure); ((Button) name.lookupButton(ButtonType.CANCEL)).fire(); }
                    });
                } catch (Throwable failure) { failed.set(failure); ((Button) pane.lookupButton(ButtonType.CANCEL)).fire(); }
            });
            ((Button) f.stage.getScene().getRoot().lookup("#library-new-" + kind)).fire(); return null;
        });
        assertThat(failed.get()).isNull();
        waitFor(() -> libraryTree(f).getSelectionModel().getSelectedItem() != null
                && libraryTree(f).getSelectionModel().getSelectedItem().getValue().name().equals("Chosen " + kind));
        var created = await(f.repository.list()).stream().filter(item -> item.name().equals("Chosen " + kind)).findFirst().orElseThrow();
        assertThat(created.parentId()).isEqualTo(collection.id());
        assertThat(created.kind()).isEqualTo(LibraryItem.Kind.valueOf(kind.toUpperCase(Locale.ROOT)));
    }

    @ParameterizedTest @ValueSource(strings = {"folder", "request"})
    void createInEmptyWorkspaceShowsExplanationAndCancelDoesNotWrite(String kind) throws Exception {
        Fixture f = open(); AtomicBoolean explained = new AtomicBoolean();
        fx(() -> {
            Platform.runLater(() -> {
                DialogPane pane = dialog(); explained.set(pane.getHeaderText().equals("No collections in this workspace"));
                ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
            });
            ((Button) f.stage.getScene().getRoot().lookup("#library-new-" + kind)).fire(); return null;
        });
        assertThat(explained).isTrue(); assertThat(await(f.repository.list())).hasSize(1);
    }

    @Test void newWorkspaceBecomesActiveBeforeCreatingItsCollection() throws Exception {
        Fixture f = open(); UUID old = f.workspace();
        createByButton(f, "#library-new-workspace", "New workspace");
        waitFor(() -> selectedWorkspace(f) != null && selectedWorkspace(f).name().equals("New workspace"));
        createByButton(f, "#library-new-collection", "New workspace collection");
        waitFor(() -> libraryTree(f).getSelectionModel().getSelectedItem() != null
                && libraryTree(f).getSelectionModel().getSelectedItem().getValue().name().equals("New workspace collection"));
        var collection = await(f.repository.list()).stream().filter(item -> item.name().equals("New workspace collection")).findFirst().orElseThrow();
        assertThat(collection.parentId()).isNotEqualTo(old);
        assertThat(collection.parentId()).isEqualTo(fx(() -> selectedWorkspace(f).id()));
    }

    @SuppressWarnings("unchecked") private static LibraryItem selectedWorkspace(Fixture f) {
        return ((ComboBox<LibraryItem>) f.stage.getScene().getRoot().lookup("#library-workspaces")).getValue();
    }

    @SuppressWarnings("unchecked") private static TreeView<LibraryItem> libraryTree(Fixture f) {
        return (TreeView<LibraryItem>) f.stage.getScene().getRoot().lookup("#library-tree");
    }
    private static void createByButton(Fixture f, String id, String name) throws Exception {
        fx(() -> {
            Platform.runLater(() -> {
                DialogPane pane = dialog(); ((TextField) pane.lookup(".text-field")).setText(name);
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
            });
            ((Button) f.stage.getScene().getRoot().lookup(id)).fire(); return null;
        });
    }

    @Test void sendsSavesClosesAndReopensAllSettingsAfterRestart() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            Fixture f = open(); var collection = await(f.repository.create(LibraryItem.Kind.COLLECTION, f.workspace(), "Collection"));
            var folder = await(f.repository.create(LibraryItem.Kind.FOLDER, collection.id(), "Folder"));
            var saved = await(f.repository.createRequest(folder.id(), "Saved request", draft(server.url("/echo").toString())));
            fx(() -> { f.controller.refresh(); return null; }); f.awaitTree(1);
            fx(() -> { f.controller.openRequest(saved.item().id()); return null; });
            waitFor(() -> f.controller.activeEditor() != null && f.controller.activeEditor().requestEditorModel().capture().equals(saved.draft()));
            fx(() -> { f.controller.activeEditor().requestEditorModel().bodyText.set("edited café"); return null; });
            server.enqueue(new MockResponse().setBody("ok"));
            fx(() -> { ((Button) f.stage.getScene().getRoot().lookup(".send-button")).fire(); return null; });
            var request = server.takeRequest(3, TimeUnit.SECONDS);
            assertThat(request).isNotNull(); assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getBody().readUtf8()).isEqualTo("edited café");
            assertThat(request.getHeader("Cookie")).isEqualTo("session=secret-cookie");
            fx(() -> { f.controller.saveActive(); return null; });
            waitFor(() -> !f.controller.activeEditor().hasUnsavedEdits());
            SavedRequest stored = await(f.repository.load(saved.item().id())); assertThat(stored.draft().bodyText()).isEqualTo("edited café");
            fx(() -> {
                TabPane tabs = (TabPane) f.stage.getScene().getRoot().lookup("#library-request-tabs");
                Event.fireEvent(tabs.getSelectionModel().getSelectedItem(), new Event(Tab.TAB_CLOSE_REQUEST_EVENT)); return null;
            });
            waitFor(() -> ((TabPane) f.stage.getScene().getRoot().lookup("#library-request-tabs")).getTabs().size() == 1);
            f.close();
            Fixture restarted = open();
            fx(() -> { restarted.controller.openRequest(saved.item().id()); return null; });
            waitFor(() -> restarted.controller.activeEditor() != null && restarted.controller.activeEditor().requestEditorModel().capture().equals(stored.draft()));
            assertThat(await(restarted.repository.recoverableDrafts())).isEmpty();
        }
    }

    @Test void recoversUnfinishedTabWithoutOverwritingSavedRequestAndNormalCloseClearsSnapshots() throws Exception {
        Fixture f = open(); var collection = await(f.repository.create(LibraryItem.Kind.COLLECTION, f.workspace(), "Collection"));
        var saved = await(f.repository.createRequest(collection.id(), "Original", draft("http://localhost/test")));
        fx(() -> { f.controller.refresh(); return null; }); f.awaitTree(1);
        fx(() -> { f.controller.openRequest(saved.item().id()); return null; });
        waitFor(() -> f.controller.activeEditor().requestEditorModel().bodyText.get().equals("saved body"));
        fx(() -> {
            var editor = f.controller.activeEditor().requestEditorModel();
            for (int i = 0; i < 40; i++) editor.bodyText.set("unfinished " + i);
            editor.timeout.set("invalid timeout draft"); return null;
        });
        waitFor(() -> ((Label) f.stage.getScene().getRoot().lookup("#library-autosave-status")).getText().equals("Recovery snapshot saved"));
        f.close(); // Abnormal exit simulation: deliberately no requestWindowClose / finishSession.
        Fixture restarted = open(); assertThat(await(restarted.repository.recoverableDrafts())).hasSize(1);
        assertThat(await(restarted.repository.recoverableDrafts()).getFirst().draft().bodyText()).isEqualTo("unfinished 39");
        fx(() -> { restarted.controller.recoverDrafts(); return null; });
        waitFor(() -> restarted.controller.activeEditor() != null && restarted.controller.activeEditor().requestEditorModel().bodyText.get().equals("unfinished 39"));
        assertThat(fx(() -> restarted.controller.activeEditor().requestEditorModel().timeout.get())).isEqualTo("invalid timeout draft");
        assertThat(await(restarted.repository.load(saved.item().id())).draft().bodyText()).isEqualTo("saved body");
        fx(() -> { restarted.controller.recoverDrafts(); return null; });
        assertThat(fx(() -> ((TabPane) restarted.stage.getScene().getRoot().lookup("#library-request-tabs")).getTabs().size())).isEqualTo(2);
        AtomicBoolean closed = new AtomicBoolean();
        fx(() -> {
            Platform.runLater(() -> dismiss(ButtonType.CANCEL));
            restarted.controller.requestWindowClose(() -> closed.set(true)); return null;
        });
        assertThat(closed).isFalse(); assertThat(fx(() -> restarted.controller.activeEditor().hasUnsavedEdits())).isTrue();
        fx(() -> {
            Platform.runLater(() -> dismiss(ButtonType.OK));
            restarted.controller.requestWindowClose(() -> { restarted.stage.hide(); closed.set(true); }); return null;
        });
        waitFor(closed::get); restarted.close();
        Fixture clean = open(); assertThat(await(clean.repository.recoverableDrafts())).isEmpty();
        assertThat(await(clean.repository.load(saved.item().id())).draft().bodyText()).isEqualTo("saved body");
    }

    @Test void databaseFailureLeavesDirtyEditorUntouchedAndUiCreationActionsWork() throws Exception {
        Fixture f = open();
        fx(() -> {
            Platform.runLater(() -> {
                DialogPane pane = dialog(); ((TextField) pane.lookup(".text-field")).setText("Created in UI");
                ((Button) pane.lookupButton(ButtonType.OK)).fire();
            });
            ((Button) f.stage.getScene().getRoot().lookup("#library-new-collection")).fire(); return null;
        });
        f.awaitTree(1);
        var parent = await(f.repository.list()).stream().filter(item -> item.kind() == LibraryItem.Kind.COLLECTION).findFirst().orElseThrow();
        assertThat(parent.name()).isEqualTo("Created in UI");
        var saved = await(f.repository.createRequest(parent.id(), "Request", draft("http://localhost/test")));
        fx(() -> { f.controller.refresh(); return null; });
        fx(() -> { f.controller.openRequest(saved.item().id()); return null; });
        waitFor(() -> f.controller.activeEditor().requestEditorModel().bodyText.get().equals("saved body"));
        fx(() -> { f.controller.activeEditor().requestEditorModel().bodyText.set("never discard this"); return null; });
        await(f.repository.shutdown());
        fx(() -> { f.controller.saveActive(); return null; });
        waitFor(() -> ((Label) f.stage.getScene().getRoot().lookup("#library-status")).getText().contains("closed"));
        assertThat(fx(() -> f.controller.activeEditor().requestEditorModel().bodyText.get())).isEqualTo("never discard this");
        assertThat(fx(() -> f.controller.activeEditor().hasUnsavedEdits())).isTrue();
    }

    private static DialogPane dialog() {
        return Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene).filter(Objects::nonNull)
                .map(Scene::getRoot).filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
    }
    private static void dismiss(ButtonType type) { ((Button) dialog().lookupButton(type)).fire(); }
    private static final class Fixture implements AutoCloseable {
        final SqliteLibraryRepository repository;
        final JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine();
        final LibraryWindowController controller;
        final Stage stage;
        private boolean closed;
        Fixture(Path file) throws Exception {
            repository = new SqliteLibraryRepository(file, new DpapiPayloadProtector()); await(repository.ready());
            controller = new LibraryWindowController(engine, repository, false);
            stage = fx(() -> { Stage stage = new Stage(); stage.setScene(new Scene(controller.createView(), 1400, 900)); stage.show(); return stage; });
            waitFor(() -> !((ComboBox<?>) stage.getScene().getRoot().lookup("#library-workspaces")).getItems().isEmpty());
        }
        UUID workspace() throws Exception { return await(repository.list()).stream().filter(item -> item.kind() == LibraryItem.Kind.WORKSPACE).findFirst().orElseThrow().id(); }
        void awaitTree(int count) throws Exception { waitFor(() -> ((TreeView<?>) stage.getScene().getRoot().lookup("#library-tree")).getRoot().getChildren().size() >= count); }
        @Override public void close() throws Exception {
            if (closed) return; closed = true;
            fx(() -> { controller.close(); stage.close(); return null; }); engine.close(); await(repository.shutdown());
        }
    }
}
