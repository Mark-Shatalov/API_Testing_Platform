package dev.apitestingplatform.httpclient;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Backpressure plus a hard byte limit, including for chunked responses. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    static final class BodyTooLargeException extends RuntimeException {
        private final byte[] prefix;
        BodyTooLargeException(byte[] prefix) { this.prefix = prefix; }
        byte[] prefix() { return prefix; }
    }
    private final int limit;
    private final BoundedBuffer bytes;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    LimitedBodySubscriber(int limit) { this.limit = limit; bytes = new BoundedBuffer(limit); }
    @Override public CompletionStage<byte[]> getBody() { return body; }
    @Override public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }
    @Override public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > limit - bytes.size()) {
                int remaining = limit - bytes.size();
                byte[] chunk = new byte[remaining];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
                subscription.cancel();
                body.completeExceptionally(new BodyTooLargeException(bytes.toByteArray()));
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }
    @Override public void onError(Throwable error) { body.completeExceptionally(error); }
    @Override public void onComplete() { body.complete(bytes.toByteArray()); }

    /** BAOS normally doubles capacity past the body limit; keep its backing allocation capped too. */
    private static final class BoundedBuffer extends ByteArrayOutputStream {
        private final int limit;
        BoundedBuffer(int limit) { super(Math.min(8192, limit)); this.limit = limit; }
        @Override public synchronized void write(byte[] value, int offset, int length) {
            java.util.Objects.checkFromIndexSize(offset, length, value.length);
            if (length > limit - count) throw new IllegalArgumentException("Body limit exceeded");
            int required = count + length;
            if (required > buf.length) {
                int capacity = (int) Math.min(limit, Math.max((long) required, (long) buf.length * 2));
                buf = java.util.Arrays.copyOf(buf, capacity);
            }
            System.arraycopy(value, offset, buf, count, length);
            count = required;
        }
    }
}
