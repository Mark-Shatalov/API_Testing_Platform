package dev.apitestingplatform.app;

import javafx.application.Platform;
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
}
