package dev.apitestingplatform.httpclient;

import dev.apitestingplatform.domain.operations.OperationalSettings;
import dev.apitestingplatform.domain.operations.OperationalSettings.ProxyMode;
import dev.apitestingplatform.domain.http.*;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class NetworkConfigurationTest {
    @TempDir Path directory;
    OperationalSettings settings(ProxyMode mode, String host, int port, String ca, int cap) { return new OperationalSettings(mode, host, port, ca, cap); }
    @Test void manualProxyReceivesAbsoluteTargetAndDirectBypassesDefaultSelector() throws Exception {
        ProxySelector previous = ProxySelector.getDefault();
        try (var proxy = new MockWebServer()) {
            proxy.start(); ProxySelector.setDefault(ProxySelector.of(InetSocketAddress.createUnresolved("localhost", proxy.getPort())));
            assertThat(NetworkConfiguration.proxy(OperationalSettings.defaults()).select(URI.create("http://example.invalid"))).containsExactly(Proxy.NO_PROXY);
            assertThat(NetworkConfiguration.proxy(settings(ProxyMode.SYSTEM, "", 8080, "", 64))).isSameAs(ProxySelector.getDefault());
            try (var engine = NetworkConfiguration.create(settings(ProxyMode.MANUAL, "localhost", proxy.getPort(), "", 64))) {
                proxy.enqueue(new MockResponse().setBody("proxied"));
                var result = engine.execute(RequestConfiguration.get("http://does-not-resolve.invalid/resource")).result().toCompletableFuture().get(10, TimeUnit.SECONDS);
                assertThat(result.statusCode()).isEqualTo(200); assertThat(proxy.takeRequest(2, TimeUnit.SECONDS).getRequestLine()).contains("http://does-not-resolve.invalid/resource");
            }
        } finally { ProxySelector.setDefault(previous); }
    }
    @Test void configuredCaptureLimitBoundsLargeResponse() throws Exception {
        try (var server = new MockWebServer(); var engine = NetworkConfiguration.create(settings(ProxyMode.DIRECT, "", 8080, "", 64))) {
            server.enqueue(new MockResponse().setBody("x".repeat(200_000)));
            var result = engine.execute(RequestConfiguration.get(server.url("/").newBuilder().host("localhost").build().toString())).result().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertThat(result.responseBody()).hasSize(64 * 1024); assertThat(result.sizeBytes()).isGreaterThanOrEqualTo(64 * 1024);
        }
    }
    @Test void additionalCaEnablesTrustedTlsAndHostnameVerificationStillRejectsWrongHost() throws Exception {
        var certificate = new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build();
        Path ca = directory.resolve("ca.pem"); Files.writeString(ca, certificate.certificatePem());
        try (var server = new MockWebServer(); var engine = NetworkConfiguration.create(settings(ProxyMode.DIRECT, "", 8080, ca.toString(), 64)); var defaultEngine = NetworkConfiguration.create(OperationalSettings.defaults())) {
            server.useHttps(new HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false); server.start(); server.enqueue(new MockResponse().setBody("secure"));
            assertThat(engine.execute(RequestConfiguration.get(server.url("/").newBuilder().host("localhost").build().toString())).result().toCompletableFuture().get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(defaultEngine.execute(RequestConfiguration.get(server.url("/").newBuilder().host("localhost").build().toString())).result().toCompletableFuture().get(10, TimeUnit.SECONDS).error()).isPresent();
            var wrongHost = server.url("/").newBuilder().host("127.0.0.1").build().toString();
            assertThat(engine.execute(RequestConfiguration.get(wrongHost)).result().toCompletableFuture().get(10, TimeUnit.SECONDS).error()).isPresent();
        }
    }
    @Test void invalidBundlePrivateKeyAndOversizeBundleAreRejected() throws Exception {
        Path bundle = directory.resolve("invalid.pem");
        for (String content : new String[]{"not a certificate", "-----BEGIN PRIVATE KEY-----", "x".repeat(1024 * 1024 + 1)}) {
            Files.writeString(bundle, content); assertThatThrownBy(() -> NetworkConfiguration.trust(bundle.toString())).isInstanceOf(java.io.IOException.class);
        }
        assertThatThrownBy(() -> settings(ProxyMode.MANUAL, "user:password@host", 8080, "", 64)).isInstanceOf(IllegalArgumentException.class);
    }
}
