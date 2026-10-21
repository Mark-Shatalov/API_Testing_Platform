package dev.apitestingplatform.httpclient;

import dev.apitestingplatform.application.http.HttpExecutionEngine;
import dev.apitestingplatform.application.http.RequestExecution;
import dev.apitestingplatform.application.http.RequestValidation;
import dev.apitestingplatform.domain.http.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Thread-safe asynchronous engine with bounded admission, executors, deadlines and body buffers.
 * No network setup, URL parsing, or body encoding runs on the caller's thread.
 * Close cancels outstanding work and initiates immediate, nonblocking client shutdown.
 */
public final class JavaHttpExecutionEngine implements HttpExecutionEngine {
    private final EngineLimits limits;
    private final HttpClient client;
    private final ThreadPoolExecutor dispatch;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor timer;
    private final Set<Operation> operations = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public JavaHttpExecutionEngine() { this(EngineLimits.defaults(), null); }
    public JavaHttpExecutionEngine(EngineLimits limits) { this(limits, null); }

    /** Custom trust context is optional; default JVM certificate and hostname validation stays enabled. */
    public JavaHttpExecutionEngine(EngineLimits limits, SSLContext sslContext) {
        this.limits = Objects.requireNonNull(limits, "limits");
        dispatch = pool(limits.workerThreads(), limits.maxConcurrentRequests(), "api-dispatch-",
                new ThreadPoolExecutor.AbortPolicy());
        workers = pool(limits.workerThreads(), Math.max(64, limits.maxConcurrentRequests() * 16),
                "api-http-", new ThreadPoolExecutor.CallerRunsPolicy());
        timer = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("api-timeout-", 0).factory());
        timer.setRemoveOnCancelPolicy(true);
        HttpClient.Builder builder = HttpClient.newBuilder().executor(workers)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (sslContext != null) builder.sslContext(sslContext);
        client = builder.build();
    }

    private static ThreadPoolExecutor pool(int threads, int queueSize, String name,
                                          RejectedExecutionHandler rejection) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize),
                Thread.ofPlatform().daemon().name(name, 0).factory(), rejection);
    }

    @Override public RequestExecution execute(RequestConfiguration request) {
        Objects.requireNonNull(request, "request");
        Operation operation = new Operation(request);
        synchronized (this) {
            if (closed) {
                operation.fail(NetworkError.Type.ENGINE_CLOSED);
                return operation;
            }
            if (operations.size() >= limits.maxConcurrentRequests()) {
                operation.fail(NetworkError.Type.RESOURCE_LIMIT);
                return operation;
            }
            operations.add(operation);
            try {
                operation.deadline = timer.schedule(() -> operation.fail(NetworkError.Type.TIMEOUT),
                        request.timeout().toNanos(), TimeUnit.NANOSECONDS);
                dispatch.execute(operation::start);
            } catch (RejectedExecutionException error) {
                operation.fail(dispatch.isShutdown() ? NetworkError.Type.ENGINE_CLOSED : NetworkError.Type.RESOURCE_LIMIT);
            }
        }
        return operation;
    }

    @Override public void close() {
        List<Operation> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = List.copyOf(operations);
        }
        pending.forEach(operation -> operation.fail(NetworkError.Type.CANCELLED));
        client.shutdownNow();
        dispatch.shutdownNow();
        workers.shutdownNow();
        timer.shutdownNow();
    }

    private final class Operation implements RequestExecution {
        private final RequestConfiguration configuration;
        private final Instant startedAt = Instant.now();
        private final long startedNanos = System.nanoTime();
        private final CompletableFuture<ExecutionResult> result = new CompletableFuture<>();
        private final Set<String> visited = new HashSet<>();
        private ScheduledFuture<?> deadline;
        private CompletableFuture<HttpResponse<byte[]>> transport;
        private boolean terminal;
        private volatile HttpResponse.ResponseInfo responseInfo;

        Operation(RequestConfiguration configuration) { this.configuration = configuration; }
        @Override public CompletionStage<ExecutionResult> result() { return result.minimalCompletionStage(); }
        @Override public boolean cancel() { return fail(NetworkError.Type.CANCELLED); }

        void start() {
            try {
                synchronized (this) { if (terminal) return; }
                if (!RequestValidation.validate(configuration).isEmpty()) throw new IllegalArgumentException("Invalid request");
                RequestBodyEncoder.Encoded body = RequestBodyEncoder.encode(configuration.body(), limits.maxRequestBodyBytes());
                URI uri = requestUri(configuration);
                send(uri, configuration.method(), body, false, false, 0);
            } catch (RequestBodyEncoder.BodyLimitException error) {
                fail(NetworkError.Type.RESOURCE_LIMIT);
            } catch (IllegalArgumentException error) {
                fail(NetworkError.Type.INVALID_REQUEST);
            } catch (java.io.IOException error) {
                fail(NetworkError.Type.INVALID_REQUEST);
            } catch (RuntimeException error) {
                fail(classify(error));
            }
        }

        private synchronized void send(URI uri, HttpMethod method, RequestBodyEncoder.Encoded body,
                                       boolean stripCredentials, boolean dropBodyHeaders, int redirects) {
            if (terminal) return;
            try {
                String visit = method + " " + uri.normalize();
                if (!visited.add(visit) || redirects > limits.maxRedirects()) {
                    fail(NetworkError.Type.REDIRECT_LIMIT);
                    return;
                }
                long remaining = configuration.timeout().toNanos() - (System.nanoTime() - startedNanos);
                if (remaining <= 0) { fail(NetworkError.Type.TIMEOUT); return; }
                HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                        .timeout(java.time.Duration.ofNanos(remaining))
                        .version(configuration.protocol() == HttpProtocol.HTTP_2
                                ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                        .method(method.name(), body.publisher());
                for (RequestField header : configuration.headers()) {
                    if (!header.enabled()) continue;
                    if (stripCredentials && sensitive(header.name())) continue;
                    if (dropBodyHeaders && bodyHeader(header.name())) continue;
                    builder.header(header.name(), header.value());
                }
                if (!stripCredentials && !configuration.cookies().isEmpty()) {
                    String cookies = String.join("; ", configuration.cookies().stream()
                            .filter(RequestField::enabled)
                            .map(JavaHttpExecutionEngine::cookie).toList());
                    if (!cookies.isEmpty()) builder.header("Cookie", cookies);
                }
                if (!dropBodyHeaders && configuration.body().type() != RequestBody.Type.NONE
                        && configuration.headers().stream().noneMatch(h -> h.enabled() && h.name().equalsIgnoreCase("Content-Type"))) {
                    builder.header("Content-Type", body.contentType());
                }
                transport = client.sendAsync(builder.build(), info -> {
                    responseInfo = info;
                    return new LimitedBodySubscriber(limits.maxResponseBytes());
                });
                transport.whenComplete((response, error) -> {
                    if (error != null) {
                        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                            if (cause instanceof LimitedBodySubscriber.BodyTooLargeException oversized) {
                                completeTruncated(oversized.prefix());
                                return;
                            }
                        }
                        fail(classify(error)); return;
                    }
                    try {
                        int status = response.statusCode();
                        Optional<String> location = response.headers().firstValue("Location");
                        if (isRedirect(status) && location.isPresent()
                                && configuration.redirects() != RedirectPolicy.NEVER) {
                            URI target = validateUri(uri.resolve(location.get()));
                            if (configuration.redirects() == RedirectPolicy.NORMAL
                                    && uri.getScheme().equalsIgnoreCase("https")
                                    && target.getScheme().equalsIgnoreCase("http")) {
                                complete(response);
                                return;
                            }
                            boolean rewrite = (status == 303 && method != HttpMethod.HEAD)
                                    || ((status == 301 || status == 302) && method == HttpMethod.POST);
                            send(target, rewrite ? HttpMethod.GET : method, rewrite ? RequestBodyEncoder.Encoded.empty() : body,
                                    stripCredentials || !sameOrigin(uri, target), dropBodyHeaders || rewrite, redirects + 1);
                        } else complete(response);
                    } catch (IllegalArgumentException invalid) {
                        fail(NetworkError.Type.INVALID_REQUEST);
                    } catch (RuntimeException failure) {
                        fail(classify(failure));
                    }
                });
            } catch (IllegalArgumentException invalid) {
                fail(NetworkError.Type.INVALID_REQUEST);
            } catch (RuntimeException failure) {
                fail(classify(failure));
            }
        }

        private synchronized void complete(HttpResponse<byte[]> response) {
            if (terminal) return;
            terminal = true;
            cleanup();
            result.complete(new ExecutionResult(response.statusCode(), response.headers().map(), response.body(),
                    elapsed(), response.body().length, Optional.empty(), startedAt,
                    Optional.of(response.version() == HttpClient.Version.HTTP_2 ? HttpProtocol.HTTP_2 : HttpProtocol.HTTP_1_1)));
        }

        private synchronized boolean fail(NetworkError.Type type) {
            if (terminal) return false;
            terminal = true;
            cleanup();
            // Mark terminal first: the transport's cancellation callback may run inline.
            if (transport != null) transport.cancel(true);
            result.complete(new ExecutionResult(0, Map.of(), new byte[0], elapsed(), 0,
                    Optional.of(new NetworkError(type, message(type))), startedAt, Optional.empty()));
            return true;
        }

        private synchronized void completeTruncated(byte[] prefix) {
            if (terminal) return;
            terminal = true;
            cleanup();
            HttpResponse.ResponseInfo info = responseInfo;
            result.complete(new ExecutionResult(info.statusCode(), info.headers().map(), prefix,
                    elapsed(), prefix.length, Optional.of(new NetworkError(NetworkError.Type.RESPONSE_TOO_LARGE,
                    "Response truncated at the configured byte limit. The remaining body was not downloaded.")),
                    startedAt, Optional.of(info.version() == HttpClient.Version.HTTP_2 ? HttpProtocol.HTTP_2 : HttpProtocol.HTTP_1_1)));
        }

        private long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos); }
        private void cleanup() {
            if (deadline != null) deadline.cancel(false);
            operations.remove(this);
        }
    }

    static NetworkError.Type classify(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException) return NetworkError.Type.DNS;
            if (cause instanceof SSLException) return NetworkError.Type.TLS;
            if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) return NetworkError.Type.TIMEOUT;
            if (cause instanceof CancellationException) return NetworkError.Type.CANCELLED;
            if (cause instanceof LimitedBodySubscriber.BodyTooLargeException) return NetworkError.Type.RESPONSE_TOO_LARGE;
            if (cause instanceof RequestBodyEncoder.BodyLimitException) return NetworkError.Type.RESOURCE_LIMIT;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) return NetworkError.Type.CONNECTION_REFUSED;
        }
        return NetworkError.Type.IO;
    }

    private static String message(NetworkError.Type type) {
        return switch (type) {
            case INVALID_REQUEST -> "Invalid URL, header, cookie, body or upload file. Check the editor; upload files must be readable.";
            case DNS -> "The server name could not be resolved.";
            case CONNECTION_REFUSED -> "A connection to the server could not be established.";
            case TLS -> "The secure connection could not be verified or established.";
            case TIMEOUT -> "The request exceeded its timeout.";
            case CANCELLED -> "The request was cancelled.";
            case REDIRECT_LIMIT -> "A redirect loop or the redirect limit was reached.";
            case RESPONSE_TOO_LARGE -> "The response exceeded the configured byte limit.";
            case RESOURCE_LIMIT -> "The engine's request capacity or request body limit was exceeded.";
            case ENGINE_CLOSED -> "The HTTP engine is closed.";
            case IO -> "A network I/O error occurred.";
        };
    }

    private static URI requestUri(RequestConfiguration configuration) {
        URI uri = validateUri(URI.create(configuration.url()));
        String base = uri.toASCIIString();
        int fragment = base.indexOf('#');
        if (fragment >= 0) base = base.substring(0, fragment);
        if (!configuration.queryParameters().isEmpty()) {
            String query = String.join("&", configuration.queryParameters().stream()
                    .filter(RequestField::enabled)
                    .map(p -> encode(p.name()) + "=" + encode(p.value())).toList());
            if (!query.isEmpty()) base += (uri.getRawQuery() == null ? "?" : uri.getRawQuery().isEmpty() ? "" : "&") + query;
        }
        return URI.create(base);
    }

    private static URI validateUri(URI uri) {
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                || uri.getPort() > 65535) throw new IllegalArgumentException("Invalid HTTP URL");
        return uri;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String cookie(RequestField cookie) {
        if (!cookie.name().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                || !cookie.value().matches("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]*")) {
            throw new IllegalArgumentException("Invalid cookie");
        }
        return cookie.name() + "=" + cookie.value();
    }

    private static boolean sensitive(String name) {
        return name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Proxy-Authorization")
                || name.equalsIgnoreCase("Cookie");
    }
    private static boolean bodyHeader(String name) {
        return name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("Content-Encoding");
    }
    private static boolean isRedirect(int status) { return status == 301 || status == 302 || status == 303 || status == 307 || status == 308; }
    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80; }
}
