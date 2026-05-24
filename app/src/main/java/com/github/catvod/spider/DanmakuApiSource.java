package com.github.catvod.spider;

import android.text.TextUtils;

public class DanmakuApiSource {

    public String url;
    public String name;
    public boolean enabled;
    public long lastLatencyMs;
    public long lastSuccessTimeMs;
    public long lastTestTimeMs;
    public String lastError;

    public DanmakuApiSource() {
        this("", true);
    }

    public DanmakuApiSource(String url, boolean enabled) {
        ParsedEntry entry = parseEntry(url);
        this.url = entry.url;
        this.name = entry.name;
        this.enabled = enabled;
        this.lastLatencyMs = -1;
        this.lastSuccessTimeMs = 0;
        this.lastTestTimeMs = 0;
        this.lastError = "";
    }

    public DanmakuApiSource(String url, String name, boolean enabled) {
        this.url = normalizeUrl(url);
        this.name = normalizeName(name);
        this.enabled = enabled;
        this.lastLatencyMs = -1;
        this.lastSuccessTimeMs = 0;
        this.lastTestTimeMs = 0;
        this.lastError = "";
    }

    public boolean isValid() {
        return !TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"));
    }

    public boolean isUsable() {
        return enabled && isValid();
    }

    public void markSuccess(long latencyMs) {
        long now = System.currentTimeMillis();
        lastLatencyMs = Math.max(0, latencyMs);
        lastSuccessTimeMs = now;
        lastTestTimeMs = now;
        lastError = "";
    }

    public void markFailure(long latencyMs, String error) {
        lastLatencyMs = Math.max(0, latencyMs);
        lastTestTimeMs = System.currentTimeMillis();
        lastError = TextUtils.isEmpty(error) ? "请求失败" : error;
    }

    public String getDisplayName(String fallback) {
        String normalizedName = normalizeName(name);
        return TextUtils.isEmpty(normalizedName) ? fallback : normalizedName;
    }

    public String toConfigEntry() {
        return formatEntry(url, name);
    }

    public static String normalizeUrl(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        int separator = trimmed.indexOf('|');
        if (separator >= 0) {
            trimmed = trimmed.substring(0, separator).trim();
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public static String normalizeName(String value) {
        return value == null ? "" : value.trim();
    }

    public static ParsedEntry parseEntry(String value) {
        if (value == null) return new ParsedEntry("", "", false);
        String trimmed = value.trim();
        int separator = trimmed.indexOf('|');
        boolean hasName = separator >= 0;
        String urlPart = hasName ? trimmed.substring(0, separator) : trimmed;
        String namePart = hasName ? trimmed.substring(separator + 1) : "";
        return new ParsedEntry(normalizeUrl(urlPart), normalizeName(namePart), hasName);
    }

    public static String formatEntry(String url, String name) {
        String normalizedUrl = normalizeUrl(url);
        String normalizedName = normalizeName(name);
        if (TextUtils.isEmpty(normalizedName)) return normalizedUrl;
        return normalizedUrl + "|" + normalizedName;
    }

    public static class ParsedEntry {
        public final String url;
        public final String name;
        public final boolean hasName;

        public ParsedEntry(String url, String name, boolean hasName) {
            this.url = url;
            this.name = name;
            this.hasName = hasName;
        }
    }
}
