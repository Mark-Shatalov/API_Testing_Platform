package dev.apitestingplatform.httpclient;

import dev.apitestingplatform.domain.operations.OperationalSettings;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** Adds trusted CA certificates to JVM trust; certificate and hostname checks are never disabled. */
public final class NetworkConfiguration {
    private NetworkConfiguration() { }
    public static JavaHttpExecutionEngine create(OperationalSettings settings) throws IOException {
        var defaults = EngineLimits.defaults();
        return new JavaHttpExecutionEngine(new EngineLimits(defaults.maxConcurrentRequests(), defaults.workerThreads(), settings.responseLimitKiB() * 1024,
                defaults.maxRequestBodyBytes(), defaults.maxRedirects()), trust(settings.additionalCaFile()), proxy(settings));
    }
    public static ProxySelector proxy(OperationalSettings settings) {
        return switch (settings.proxyMode()) {
            case DIRECT -> ProxySelector.of(null);
            case SYSTEM -> Optional.ofNullable(ProxySelector.getDefault()).orElseGet(() -> ProxySelector.of(null));
            case MANUAL -> ProxySelector.of(InetSocketAddress.createUnresolved(settings.proxyHost().replace("[", "").replace("]", ""), settings.proxyPort()));
        };
    }
    public static SSLContext trust(String certificateFile) throws IOException {
        if (certificateFile.isEmpty()) return null;
        try (var input = Files.newInputStream(Path.of(certificateFile))) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024 || new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).contains("PRIVATE KEY")) throw new IOException("Choose a certificate-only PEM/DER bundle of at most 1 MiB; private keys are not accepted.");
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes));
            if (certificates.isEmpty() || certificates.size() > 100) throw new IOException("Choose a bundle containing 1–100 X.509 certificates.");
            KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType()); extra.load(null);
            int index = 0; for (var certificate : certificates) { if (!(certificate instanceof X509Certificate x509)) throw new CertificateException(); x509.checkValidity(); extra.setCertificateEntry("extra-" + index++, x509); }
            X509TrustManager standard = manager(null), additional = manager(extra);
            X509TrustManager combined = new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return java.util.stream.Stream.concat(Arrays.stream(standard.getAcceptedIssuers()), Arrays.stream(additional.getAcceptedIssuers())).toArray(X509Certificate[]::new); }
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException { try { standard.checkServerTrusted(chain, auth); } catch (CertificateException unknown) { additional.checkServerTrusted(chain, auth); } }
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { try { standard.checkClientTrusted(chain, auth); } catch (CertificateException unknown) { additional.checkClientTrusted(chain, auth); } }
            };
            SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{combined}, null); return context;
        } catch (GeneralSecurityException | IllegalArgumentException failure) { throw new IOException("The additional CA bundle could not be loaded. Check its format, validity and file access; JVM trust remains available."); }
    }
    private static X509TrustManager manager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(store);
        return Arrays.stream(factory.getTrustManagers()).filter(X509TrustManager.class::isInstance).map(X509TrustManager.class::cast).findFirst().orElseThrow(KeyStoreException::new);
    }
}
