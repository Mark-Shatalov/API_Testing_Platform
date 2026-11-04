package dev.apitestingplatform.app;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

final class FxTestSupport {
    private static boolean started;
    static synchronized void start() throws Exception {
        if (started) return;
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); started = true;
        // Surefire ends the fork after all suites; do not terminate the shared toolkit between classes.
    }
    static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
    }
    static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { if (fx(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("UI did not reach expected state");
    }
    /** Invoke a visible command or an action moved into a named menu. FX thread only. */
    static void fireAction(Parent root, String selector) {
        if (root.lookup(selector) instanceof Button button) { button.fire(); return; }
        menuAction(root, selector).fire();
    }
    static MenuItem menuAction(Parent root, String selector) {
        String id = selector.startsWith("#") ? selector.substring(1) : selector;
        for (var node : root.lookupAll(".menu-button")) if (node instanceof MenuButton menu) {
            MenuItem found = find(menu.getItems(), id);
            if (found != null) return found;
        }
        throw new AssertionError("Missing menu action: " + selector);
    }
    private static MenuItem find(java.util.List<MenuItem> items, String id) {
        for (var item : items) {
            if (id.equals(item.getId())) return item;
            if (item instanceof Menu menu) { MenuItem found = find(menu.getItems(), id); if (found != null) return found; }
        }
        return null;
    }
}
