package dev.apitestingplatform.ui.response;

import dev.apitestingplatform.domain.http.ExecutionResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Background file saving preserves captured bytes exactly, independently of display mode and charset. */
public final class ResponseBodyFiles {
    private ResponseBodyFiles() { }

    public static void save(ExecutionResult result, Path destination) throws IOException {
        Path target = destination.toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), ".api-response-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = result.bodyBuffer();
                bytes.limit(Math.min(bytes.remaining(), ResponsePresentation.BODY_LIMIT));
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(false);
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
