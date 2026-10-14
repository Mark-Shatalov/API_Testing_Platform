package dev.apitestingplatform.httpclient;

import dev.apitestingplatform.application.http.RequestExecution;
import dev.apitestingplatform.domain.http.*;
import okhttp3.Protocol;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JavaHttpExecutionEngineTest {
    private static RequestConfiguration request(HttpMethod method, String url, RequestBody body,
                                                 Duration timeout, RedirectPolicy redirects, HttpProtocol protocol) {
        return new RequestConfiguration(method, url, List.of(), List.of(), List.of(), body, timeout, redirects, protocol);
    }

    private static ExecutionResult await(RequestExecution execution) throws Exception {
        return execution.result().toCompletableFuture().get(8, TimeUnit.SECONDS);
    }

    private static void assertError(ExecutionResult result, NetworkError.Type type) {
        assertThat(result.error()).isPresent();
        assertThat(result.error().orElseThrow().type()).isEqualTo(type);
        assertThat(result.statusCode()).isZero();
        assertThat(result.durationMillis()).isNotNegative();
    }

    @ParameterizedTest
    @EnumSource(HttpMethod.class)
    void executesEverySupportedMethod(HttpMethod method) throws Exception {
        try (MockWebServer server = new MockWebServer(); JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            server.enqueue(new MockResponse().setResponseCode(204));
            ExecutionResult result = await(engine.execute(request(method, server.url("/methods").toString(),
                    RequestBody.none(), Duration.ofSeconds(3), RedirectPolicy.NORMAL, HttpProtocol.HTTP_1_1)));
            assertThat(result.statusCode()).isEqualTo(204);
            assertThat(result.error()).isEmpty();
            assertThat(server.takeRequest(2, TimeUnit.SECONDS).getMethod()).isEqualTo(method.name());
        }
    }

    @Test
    void sendsEncodedDuplicateQueryParametersHeadersCookiesAndJson() throws Exception {
        try (MockWebServer server = new MockWebServer(); JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            server.enqueue(new MockResponse().setResponseCode(201).addHeader("X-Reply", "first")
                    .addHeader("X-Reply", "second").setBody("héllo"));
            RequestConfiguration configuration = new RequestConfiguration(HttpMethod.POST,
                    server.url("/resource?existing=1").toString() + "#ignored",
                    List.of(new RequestField("q", "a b&+"), new RequestField("q", "東京")),
                    List.of(new RequestField("X-Custom", "value")),
                    List.of(new RequestField("session", "abc"), new RequestField("theme", "dark")),
                    RequestBody.json("{\"name\":\"Alice\"}"), Duration.ofSeconds(3), RedirectPolicy.NORMAL, HttpProtocol.HTTP_1_1);
            Instant before = Instant.now();
            ExecutionResult result = await(engine.execute(configuration));
            RecordedRequest received = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(received.getPath()).isEqualTo("/resource?existing=1&q=a%20b%26%2B&q=%E6%9D%B1%E4%BA%AC");
            assertThat(received.getHeader("X-Custom")).isEqualTo("value");
            assertThat(received.getHeader("Cookie")).isEqualTo("session=abc; theme=dark");
            assertThat(received.getHeader("Content-Type")).isEqualTo("application/json; charset=UTF-8");
            assertThat(received.getBody().readUtf8()).isEqualTo(configuration.body().content());
            assertThat(result.statusCode()).isEqualTo(201);
            assertThat(result.responseHeaders().get("x-reply")).containsExactly("first", "second");
            assertThat(result.responseBody()).isEqualTo("héllo".getBytes(StandardCharsets.UTF_8));
            assertThat(result.sizeBytes()).isEqualTo(6);
            assertThat(result.startedAt()).isAfterOrEqualTo(before);
            assertThat(result.protocol()).contains(HttpProtocol.HTTP_1_1);
            byte[] exposed = result.responseBody();
            exposed[0] = 0;
            assertThat(result.responseBody()[0]).isEqualTo((byte) 'h');
        }
    }

    @Test
    void sendsTextAndHonorsExplicitContentType() throws Exception {
        try (MockWebServer server = new MockWebServer(); JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            server.enqueue(new MockResponse().setBody("ok"));
            RequestConfiguration configuration = new RequestConfiguration(HttpMethod.PUT, server.url("/").toString(),
                    List.of(), List.of(new RequestField("Content-Type", "text/custom")), List.of(),
                    RequestBody.text("Unicode: €"), Duration.ofSeconds(3), RedirectPolicy.NEVER, HttpProtocol.HTTP_1_1);
            assertThat(await(engine.execute(configuration)).succeeded()).isTrue();
            RecordedRequest received = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(received.getBody().readUtf8()).isEqualTo("Unicode: €");
            assertThat(received.getHeader("Content-Type")).isEqualTo("text/custom");
        }
    }

    @Test
    void returnsHttpErrorStatusAsACompletedResponse() throws Exception {
        try (MockWebServer server = new MockWebServer(); JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("server error"));
            ExecutionResult result = await(engine.execute(RequestConfiguration.get(server.url("/").toString())));
            assertThat(result.succeeded()).isTrue();
            assertThat(result.statusCode()).isEqualTo(500);
        }
    }

    @Test
    void handlesRedirectsAndNeverPolicy() throws Exception {
        try (MockWebServer server = new MockWebServer(); JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/next"));
            server.enqueue(new MockResponse().setBody("final"));
            assertThat(await(engine.execute(RequestConfiguration.get(server.url("/start").toString()))).statusCode()).isEqualTo(200);
            assertThat(server.takeRequest().getPath()).isEqualTo("/start");
            assertThat(server.takeRequest().getPath()).isEqualTo("/next");
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/next"));
            assertThat(await(engine.execute(request(HttpMethod.GET, server.url("/start").toString(), RequestBody.none(),
                    Duration.ofSeconds(3), RedirectPolicy.NEVER, HttpProtocol.HTTP_1_1))).statusCode()).isEqualTo(302);
            assertThat(server.getRequestCount()).isEqualTo(3);
        }
    }

    @Test
    void detectsRedirectLoopsAndRedirectLimit() throws Exception {
        try (MockWebServer server = new MockWebServer();
             JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine(new EngineLimits(2, 2, 1024, 1024, 2))) {
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/loop"));
            assertError(await(engine.execute(RequestConfiguration.get(server.url("/loop").toString()))), NetworkError.Type.REDIRECT_LIMIT);
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    int number = Integer.parseInt(request.getPath().substring(1));
                    return new MockResponse().setResponseCode(302).addHeader("Location", "/" + (number + 1));
                }
            });
            assertError(await(engine.execute(RequestConfiguration.get(server.url("/0").toString()))), NetworkError.Type.REDIRECT_LIMIT);
            assertThat(server.getRequestCount()).isEqualTo(4);
        }
    }

    @Test
    void stripsCredentialsWhenRedirectChangesOriginAndRewritesPost() throws Exception {
        try (MockWebServer source = new MockWebServer(); MockWebServer destination = new MockWebServer();
             JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            destination.enqueue(new MockResponse().setBody("ok"));
            source.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", destination.url("/").toString()));
            RequestConfiguration configuration = new RequestConfiguration(HttpMethod.POST, source.url("/").toString(),
                    List.of(), List.of(new RequestField("Authorization", "Bearer secret")),
                    List.of(new RequestField("session", "secret")), RequestBody.text("payload"),
                    Duration.ofSeconds(3), RedirectPolicy.ALWAYS, HttpProtocol.HTTP_1_1);
            assertThat(await(engine.execute(configuration)).statusCode()).isEqualTo(200);
            RecordedRequest redirected = destination.takeRequest(2, TimeUnit.SECONDS);
            assertThat(redirected.getMethod()).isEqualTo("GET");
            assertThat(redirected.getBodySize()).isZero();
            assertThat(redirected.getHeader("Authorization")).isNull();
            assertThat(redirected.getHeader("Cookie")).isNull();
            assertThat(redirected.getHeader("Content-Type")).isNull();
        }
    }

    @Test
    void preservesBodyOn307Redirect() throws Exception {
        try (MockWebServer server = new MockWebServer(); JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location", "/next"));
            server.enqueue(new MockResponse());
            assertThat(await(engine.execute(request(HttpMethod.PATCH, server.url("/").toString(), RequestBody.text("payload"),
                    Duration.ofSeconds(3), RedirectPolicy.NORMAL, HttpProtocol.HTTP_1_1))).statusCode()).isEqualTo(200);
            server.takeRequest();
            RecordedRequest redirected = server.takeRequest();
            assertThat(redirected.getMethod()).isEqualTo("PATCH");
            assertThat(redirected.getBody().readUtf8()).isEqualTo("payload");
            assertThat(redirected.getHeader("Content-Type")).isEqualTo("text/plain; charset=UTF-8");
        }
    }

    @Test
    void enforcesTimeoutAcrossBodyReadingAndReleasesCapacity() throws Exception {
        try (MockWebServer server = new MockWebServer();
             JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine(new EngineLimits(1, 2, 1024, 1024, 5))) {
            server.enqueue(new MockResponse().setBody("slow body").setBodyDelay(500, TimeUnit.MILLISECONDS));
            assertError(await(engine.execute(request(HttpMethod.GET, server.url("/").toString(), RequestBody.none(),
                    Duration.ofMillis(150), RedirectPolicy.NORMAL, HttpProtocol.HTTP_1_1))), NetworkError.Type.TIMEOUT);
            server.enqueue(new MockResponse().setBody("ok"));
            assertThat(await(engine.execute(RequestConfiguration.get(server.url("/").toString()))).succeeded()).isTrue();
        }
    }

    @Test
    void supportsNonblockingCancellationAndBoundedAdmission() throws Exception {
        try (MockWebServer server = new MockWebServer();
             JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine(new EngineLimits(1, 2, 1024, 1024, 5))) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            long before = System.nanoTime();
            RequestExecution pending = engine.execute(RequestConfiguration.get(server.url("/").toString()));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)).isLessThan(500);
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            assertError(await(engine.execute(RequestConfiguration.get(server.url("/").toString()))), NetworkError.Type.RESOURCE_LIMIT);
            assertThat(pending.cancel()).isTrue();
            assertError(await(pending), NetworkError.Type.CANCELLED);
            assertThat(pending.cancel()).isFalse();
        }
    }

    @Test
    void rejectsInvalidUrlsAndHeaderOrCookieInjectionWithoutLeakingSecrets() throws Exception {
        try (JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine(); MockWebServer server = new MockWebServer()) {
            for (String invalid : List.of("not a url", "file:///tmp/test", "http://", "http://user:secret@localhost/", "http://localhost:99999")) {
                ExecutionResult result = await(engine.execute(RequestConfiguration.get(invalid)));
                assertError(result, NetworkError.Type.INVALID_REQUEST);
                assertThat(result.error().orElseThrow().message()).doesNotContain("secret");
            }
            for (boolean header : List.of(true, false)) {
                RequestField injected = new RequestField("X-Test", "secret\r\nInjected: yes");
                RequestConfiguration config = new RequestConfiguration(HttpMethod.GET, server.url("/").toString(), List.of(),
                        header ? List.of(injected) : List.of(), header ? List.of() : List.of(injected), RequestBody.none(),
                        Duration.ofSeconds(3), RedirectPolicy.NORMAL, HttpProtocol.HTTP_1_1);
                assertError(await(engine.execute(config)), NetworkError.Type.INVALID_REQUEST);
            }
            assertThat(server.getRequestCount()).isZero();
        }
    }

    @Test
    void reportsConnectionRefusedAndDnsFailures() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) { unusedPort = socket.getLocalPort(); }
        try (JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            assertError(await(engine.execute(RequestConfiguration.get("http://127.0.0.1:" + unusedPort))), NetworkError.Type.CONNECTION_REFUSED);
            assertError(await(engine.execute(RequestConfiguration.get("http://does-not-exist.invalid/"))), NetworkError.Type.DNS);
            assertThat(JavaHttpExecutionEngine.classify(new RuntimeException(new UnknownHostException()))).isEqualTo(NetworkError.Type.DNS);
        }
    }

    @Test
    void capsChunkedResponsesAndRequestBodies() throws Exception {
        try (MockWebServer server = new MockWebServer();
             JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine(new EngineLimits(2, 2, 4, 4, 5))) {
            server.enqueue(new MockResponse().setChunkedBody("123456789", 2));
            ExecutionResult oversized = await(engine.execute(RequestConfiguration.get(server.url("/").toString())));
            assertThat(oversized.error().orElseThrow().type()).isEqualTo(NetworkError.Type.RESPONSE_TOO_LARGE);
            assertThat(oversized.statusCode()).isEqualTo(200);
            assertThat(oversized.responseBody()).isEqualTo("1234".getBytes(StandardCharsets.UTF_8));
            assertThat(oversized.bodyTruncated()).isTrue();
            assertError(await(engine.execute(request(HttpMethod.POST, server.url("/").toString(), RequestBody.text("12345"),
                    Duration.ofSeconds(3), RedirectPolicy.NORMAL, HttpProtocol.HTTP_1_1))), NetworkError.Type.RESOURCE_LIMIT);
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void closeCancelsOutstandingRequestsAndRejectsNewOnes() throws Exception {
        try (MockWebServer server = new MockWebServer(); JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            RequestExecution pending = engine.execute(RequestConfiguration.get(server.url("/").toString()));
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            engine.close();
            assertError(await(pending), NetworkError.Type.CANCELLED);
            assertError(await(engine.execute(RequestConfiguration.get(server.url("/").toString()))), NetworkError.Type.ENGINE_CLOSED);
        }
    }

    @Test
    void negotiatesHttpsHttp2AndReportsUntrustedCertificates() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder().commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .addSubjectAlternativeName(InetAddress.getLoopbackAddress().getCanonicalHostName()).build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates trustedCertificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate()).build();
        try (MockWebServer server = new MockWebServer();
             JavaHttpExecutionEngine trusted = new JavaHttpExecutionEngine(EngineLimits.defaults(), trustedCertificates.sslContext());
             JavaHttpExecutionEngine untrusted = new JavaHttpExecutionEngine()) {
            server.useHttps(serverCertificates.sslSocketFactory(), false);
            server.setProtocols(List.of(Protocol.HTTP_2, Protocol.HTTP_1_1));
            server.start(InetAddress.getLoopbackAddress(), 0);
            server.enqueue(new MockResponse().setBody("secure"));
            ExecutionResult result = await(trusted.execute(RequestConfiguration.get(server.url("/").toString())));
            assertThat(result.error()).isEmpty();
            assertThat(result.protocol()).contains(HttpProtocol.HTTP_2);
            assertError(await(untrusted.execute(RequestConfiguration.get(server.url("/").toString()))), NetworkError.Type.TLS);
        }
    }

    @Test
    void normalPolicyBlocksHttpsDowngradeWhileAlwaysAllowsIt() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder().commonName("localhost")
                .addSubjectAlternativeName(InetAddress.getLoopbackAddress().getCanonicalHostName()).build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates trust = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        try (MockWebServer secure = new MockWebServer(); MockWebServer plain = new MockWebServer();
             JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine(EngineLimits.defaults(), trust.sslContext())) {
            secure.useHttps(serverCertificates.sslSocketFactory(), false);
            secure.start(InetAddress.getLoopbackAddress(), 0);
            secure.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", plain.url("/").toString()));
            ExecutionResult stopped = await(engine.execute(RequestConfiguration.get(secure.url("/").toString())));
            assertThat(stopped.statusCode()).isEqualTo(302);
            assertThat(plain.getRequestCount()).isZero();
            secure.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", plain.url("/").toString()));
            plain.enqueue(new MockResponse().setBody("ok"));
            ExecutionResult followed = await(engine.execute(request(HttpMethod.GET, secure.url("/").toString(), RequestBody.none(),
                    Duration.ofSeconds(3), RedirectPolicy.ALWAYS, HttpProtocol.HTTP_1_1)));
            assertThat(followed.statusCode()).isEqualTo(200);
            assertThat(followed.protocol()).contains(HttpProtocol.HTTP_1_1);
        }
    }

    @Test
    void usesOneDeadlineForEntireRedirectChain() throws Exception {
        try (MockWebServer server = new MockWebServer(); JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", "/next")
                    .setHeadersDelay(150, TimeUnit.MILLISECONDS));
            server.enqueue(new MockResponse().setBody("late").setHeadersDelay(500, TimeUnit.MILLISECONDS));
            ExecutionResult result = await(engine.execute(request(HttpMethod.GET, server.url("/").toString(), RequestBody.none(),
                    Duration.ofMillis(300), RedirectPolicy.NORMAL, HttpProtocol.HTTP_1_1)));
            assertError(result, NetworkError.Type.TIMEOUT);
            assertThat(result.durationMillis()).isLessThan(1000);
        }
    }

    @Test
    void executesConcurrentRequestsAndCancellationWithoutLosingCapacity() throws Exception {
        try (MockWebServer server = new MockWebServer();
             JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine(new EngineLimits(16, 2, 1024, 1024, 5))) {
            for (int i = 0; i < 12; i++) server.enqueue(new MockResponse().setBody("ok"));
            List<RequestExecution> pending = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) pending.add(engine.execute(RequestConfiguration.get(server.url("/" + i).toString())));
            for (RequestExecution execution : pending) assertThat(await(execution).statusCode()).isEqualTo(200);
            for (int i = 0; i < 12; i++) {
                RequestExecution execution = engine.execute(RequestConfiguration.get(server.url("/cancel").toString()));
                execution.cancel();
                assertError(await(execution), NetworkError.Type.CANCELLED);
            }
        }
    }

    @Test
    void completesSuccessfulNetworkWorkAwayFromTheCallingThread() throws Exception {
        try (MockWebServer server = new MockWebServer(); JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            server.enqueue(new MockResponse().setBody("ok").setHeadersDelay(100, TimeUnit.MILLISECONDS));
            Thread caller = Thread.currentThread();
            RequestExecution execution = engine.execute(RequestConfiguration.get(server.url("/").toString()));
            Thread completionThread = execution.result().thenApply(result -> Thread.currentThread())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(completionThread).isNotSameAs(caller);
            assertThat(await(execution).succeeded()).isTrue();
        }
    }

    @Test
    void reportsInterruptedResponseAsIoFailure() throws Exception {
        try (MockWebServer server = new MockWebServer(); JavaHttpExecutionEngine engine = new JavaHttpExecutionEngine()) {
            server.enqueue(new MockResponse().setBody("response content")
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
            assertError(await(engine.execute(RequestConfiguration.get(server.url("/").toString()))), NetworkError.Type.IO);
        }
    }
}
