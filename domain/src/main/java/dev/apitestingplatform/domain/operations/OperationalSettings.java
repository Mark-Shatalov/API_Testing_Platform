package dev.apitestingplatform.domain.operations;

import java.util.Objects;

/** Network changes are applied on restart; no credentials or certificate contents are stored here. */
public record OperationalSettings(ProxyMode proxyMode, String proxyHost, int proxyPort, String additionalCaFile, int responseLimitKiB) {
    public enum ProxyMode { DIRECT, SYSTEM, MANUAL }
    public OperationalSettings {
        Objects.requireNonNull(proxyMode); proxyHost = Objects.requireNonNull(proxyHost).strip(); additionalCaFile = Objects.requireNonNull(additionalCaFile).strip();
        if (proxyPort < 1 || proxyPort > 65535 || responseLimitKiB < 64 || responseLimitKiB > 10240 || additionalCaFile.length() > 32767)
            throw new IllegalArgumentException("Use a proxy port of 1–65535 and response capture limit of 64–10240 KiB.");
        if (!proxyHost.isEmpty() && !proxyHost.matches("[A-Za-z0-9._:\\[\\]-]{1,253}")) throw new IllegalArgumentException("Enter a proxy hostname or IP address without a URL, credentials or port suffix.");
        if (proxyHost.contains(":") && (proxyHost.chars().filter(character -> character == ':').count() < 2 || !proxyHost.matches("\\[?[A-Fa-f0-9:]+\\]?")))
            throw new IllegalArgumentException("Enter a hostname or IPv6 address; use the separate Port field for the proxy port.");
        if (proxyMode == ProxyMode.MANUAL && proxyHost.isEmpty()) throw new IllegalArgumentException("A manual proxy needs a hostname or IP address.");
    }
    public static OperationalSettings defaults() { return new OperationalSettings(ProxyMode.DIRECT, "", 8080, "", 10240); }
    @Override public String toString() { return "OperationalSettings[" + proxyMode + ", extra CA=" + !additionalCaFile.isEmpty() + ", response limit=" + responseLimitKiB + " KiB]"; }
}
