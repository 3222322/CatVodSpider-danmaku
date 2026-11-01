package com.github.catvod.spider;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 弹幕配置实体类
 */
public class DanmakuConfig {
    public static final String DEFAULT_DANMAKU_STYLE = "模板五";

    /**
     * 内置 TMDB v3 API Key。用户可在局域网配置页扫码替换。
     */
    public static final String DEFAULT_TMDB_API_KEY = "c2d8bcc1ff01b54ee5039d5b70f2b67e";
    /**
     * 内置弹幕搜索 API。用户可在局域网配置页扫码替换。
     */
    public static final String DEFAULT_DANMAKU_API_URL = "http://192.168.1.2:9321/87654321";
    /**
     * 弹幕API地址
     */
    public Set<String> apiUrls;
    /**
     * 弹幕搜索框宽度比例
     */
    public float lpWidth;
    /**
     * 弹幕搜索框高度比例
     */
    public float lpHeight;
    /**
     * 弹幕搜索框透明度
     */
    public float lpAlpha;
    /**
     * 自动推送弹幕开关
     */
    public boolean autoPushEnabled;
    /**
     * 弹幕搜索框样式
     */
    public String danmakuStyle;
    /**
     * 静默模式，开启时不再弹出任何提示信息
     */
    public boolean silentMode;
    /**
     * Auto 去片头尾开关
     */
    public boolean autoSkipEnabled;
    /**
     * TMDB v3 API Key 或 v4 Read Access Token
     */
    public String tmdbApiKey;
    /**
     * TMDB 查询语言
     */
    public String tmdbLanguage;
    /**
     * 代理类型：0=未设置，1=Go代理，2=Java代理
     */
    public int proxyType;

    public DanmakuConfig() {
        // 设置默认值
        apiUrls = new HashSet<>();
        apiUrls.add(DEFAULT_DANMAKU_API_URL);
        lpWidth = 0.9f;
        lpHeight = 0.85f;
        lpAlpha = 0.9f;
        autoPushEnabled = false;
        danmakuStyle = DEFAULT_DANMAKU_STYLE;
        silentMode = true;
        autoSkipEnabled = true;
        tmdbApiKey = DEFAULT_TMDB_API_KEY;
        tmdbLanguage = "zh-CN";
        proxyType = 1;
    }

    public void updateFromJson(JSONObject json) {
        if (json == null) return;
        if (json.has("apiUrl")) {
            apiUrls.addAll(Arrays.asList(json.optString("apiUrl").split(",")));
        }
        if (json.has("lpWidth")) {
            setLpWidth((float) json.optDouble("lpWidth", lpWidth));
        }
        if (json.has("lpHeight")) {
            setLpHeight((float) json.optDouble("lpHeight", lpHeight));
        }
        if (json.has("lpAlpha")) {
            setLpAlpha((float) json.optDouble("lpAlpha", lpAlpha));
        }
        if (json.has("autoPushEnabled")) {
            setAutoPushEnabled(json.optBoolean("autoPushEnabled", autoPushEnabled));
        }
        if (json.has("danmakuStyle")) {
            setDanmakuStyle(json.optString("danmakuStyle", danmakuStyle));
        }
        if (json.has("silentMode")) {
            setSilentMode(json.optBoolean("silentMode", silentMode));
        }
        if (json.has("autoSkipEnabled")) {
            setAutoSkipEnabled(json.optBoolean("autoSkipEnabled", autoSkipEnabled));
        }
        if (json.has("tmdbApiKey")) {
            setTmdbApiKey(json.optString("tmdbApiKey", tmdbApiKey));
        }
        if (json.has("tmdbLanguage")) {
            setTmdbLanguage(json.optString("tmdbLanguage", tmdbLanguage));
        }
        if (json.has("proxyType")) {
            setProxyType(json.optInt("proxyType", proxyType));
        }
    }

    public Set<String> getApiUrls() {
        return apiUrls;
    }

    public void setApiUrls(Set<String> apiUrls) {
        this.apiUrls = apiUrls;
    }

    public float getLpWidth() {
        return lpWidth;
    }

    public void setLpWidth(float lpWidth) {
        this.lpWidth = lpWidth;
    }

    public float getLpHeight() {
        return lpHeight;
    }

    public void setLpHeight(float lpHeight) {
        this.lpHeight = lpHeight;
    }

    public float getLpAlpha() {
        return lpAlpha;
    }

    public void setLpAlpha(float lpAlpha) {
        this.lpAlpha = lpAlpha;
    }

    public boolean isAutoPushEnabled() {
        return autoPushEnabled;
    }

    public void setAutoPushEnabled(boolean autoPushEnabled) {
        this.autoPushEnabled = autoPushEnabled;
    }

    public String getDanmakuStyle() {
        return DEFAULT_DANMAKU_STYLE;
    }

    public void setDanmakuStyle(String danmakuStyle) {
        this.danmakuStyle = DEFAULT_DANMAKU_STYLE;
    }

    public boolean isSilentMode() {
        return silentMode;
    }

    public void setSilentMode(boolean silentMode) {
        this.silentMode = silentMode;
    }

    public boolean isAutoSkipEnabled() {
        return autoSkipEnabled;
    }

    public void setAutoSkipEnabled(boolean autoSkipEnabled) {
        this.autoSkipEnabled = autoSkipEnabled;
    }

    public String getTmdbApiKey() {
        return tmdbApiKey;
    }

    public void setTmdbApiKey(String tmdbApiKey) {
        String value = tmdbApiKey == null ? "" : tmdbApiKey.trim();
        this.tmdbApiKey = value.isEmpty() ? DEFAULT_TMDB_API_KEY : value;
    }

    public String getTmdbLanguage() {
        return tmdbLanguage;
    }

    public void setTmdbLanguage(String tmdbLanguage) {
        String value = tmdbLanguage == null ? "" : tmdbLanguage.trim();
        this.tmdbLanguage = value.isEmpty() ? "zh-CN" : value;
    }

    public int getProxyType() {
        return proxyType;
    }

    public void setProxyType(int proxyType) {
        if (proxyType < 0) proxyType = 0;
        if (proxyType > 2) proxyType = 0;
        this.proxyType = proxyType;
    }
}
