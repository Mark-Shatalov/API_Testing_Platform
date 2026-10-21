package dev.apitestingplatform.httpclient;

import dev.apitestingplatform.domain.http.MultipartPart;
import dev.apitestingplatform.domain.http.RequestBody;
import dev.apitestingplatform.domain.http.RequestField;

import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Invoked on dispatch workers only. File publishers are replayable for 307/308 redirects. */
final class RequestBodyEncoder {
    record Encoded(BodyPublisher publisher, String contentType) {
        static Encoded empty() { return new Encoded(BodyPublishers.noBody(), ""); }
    }

    static Encoded encode(RequestBody body, int limit) throws IOException {
        if (body.type() == RequestBody.Type.NONE) return Encoded.empty();
        if (body.type() == RequestBody.Type.MULTIPART) return multipart(body.parts(), limit);
        String content = body.content();
        String type = body.type() == RequestBody.Type.JSON ? "application/json; charset=UTF-8" : "text/plain; charset=UTF-8";
        if (body.type() == RequestBody.Type.FORM_URLENCODED) {
            StringBuilder encoded = new StringBuilder();
            for (RequestField field : body.fields()) {
                if (!field.enabled()) continue;
                if (field.name().length() > limit || field.value().length() > limit) throw new BodyLimitException();
                if (!encoded.isEmpty()) encoded.append('&');
                encoded.append(formEncode(field.name())).append('=').append(formEncode(field.value()));
                if (encoded.length() > limit) throw new BodyLimitException();
            }
            content = encoded.toString();
            type = "application/x-www-form-urlencoded; charset=UTF-8";
        }
        if (content.length() > limit) throw new BodyLimitException();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new BodyLimitException();
        return new Encoded(BodyPublishers.ofByteArray(bytes), type);
    }

    private static Encoded multipart(List<MultipartPart> parts, int limit) throws IOException {
        String boundary = "ApiPlatform-" + UUID.randomUUID();
        List<BodyPublisher> publishers = new ArrayList<>();
        long total = 0;
        for (MultipartPart part : parts) {
            if (!part.enabled()) continue;
            String disposition = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part.name() + "\"";
            if (part.file().isPresent()) disposition += "; filename=\"" + part.file().get().getFileName() + "\"";
            byte[] header = (disposition + "\r\nContent-Type: " + part.contentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            total += header.length + 2L;
            publishers.add(BodyPublishers.ofByteArray(header));
            if (part.file().isPresent()) {
                var path = part.file().get();
                if (!Files.isRegularFile(path) || !Files.isReadable(path)) throw new IllegalArgumentException("Upload file is not readable");
                total += Files.size(path);
                if (total > limit) throw new BodyLimitException();
                publishers.add(BodyPublishers.ofFile(path));
            } else {
                if (part.value().length() > limit) throw new BodyLimitException();
                byte[] value = part.value().getBytes(StandardCharsets.UTF_8);
                total += value.length;
                if (total > limit) throw new BodyLimitException();
                publishers.add(BodyPublishers.ofByteArray(value));
            }
            publishers.add(BodyPublishers.ofByteArray(new byte[]{13, 10}));
        }
        byte[] end = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        if (total + end.length > limit) throw new BodyLimitException();
        publishers.add(BodyPublishers.ofByteArray(end));
        return new Encoded(capped(BodyPublishers.concat(publishers.toArray(BodyPublisher[]::new)), limit),
                "multipart/form-data; boundary=" + boundary);
    }

    /** Also enforce the limit at subscription time if a selected file changes after preparation. */
    private static BodyPublisher capped(BodyPublisher delegate, int limit) {
        return new BodyPublisher() {
            @Override public long contentLength() { return delegate.contentLength(); }
            @Override public void subscribe(java.util.concurrent.Flow.Subscriber<? super java.nio.ByteBuffer> downstream) {
                delegate.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                    private java.util.concurrent.Flow.Subscription subscription;
                    private long sent;
                    private boolean done;
                    @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
                        subscription = value; downstream.onSubscribe(value);
                    }
                    @Override public void onNext(java.nio.ByteBuffer value) {
                        if (done) return;
                        if (value.remaining() > limit - sent) {
                            done = true; subscription.cancel(); downstream.onError(new BodyLimitException()); return;
                        }
                        sent += value.remaining(); downstream.onNext(value);
                    }
                    @Override public void onError(Throwable error) { if (!done) { done = true; downstream.onError(error); } }
                    @Override public void onComplete() { if (!done) { done = true; downstream.onComplete(); } }
                });
            }
        };
    }

    private static String formEncode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    static final class BodyLimitException extends IllegalArgumentException { }
}
