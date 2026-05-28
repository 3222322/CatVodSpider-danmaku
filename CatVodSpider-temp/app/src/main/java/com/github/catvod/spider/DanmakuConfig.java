package com.github.catvod.spider;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 弹幕配置实体类
 */
public class DanmakuConfig {
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

    public DanmakuConfig() {
        // 设置默认值
        apiUrls = new HashSet<>();
        lpWidth = 0.9f;
        lpHeight = 0.85f;
        lpAlpha = 0.9f;
        autoPushEnabled = false;
        danmakuStyle = "模板一";
        silentMode = true;
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
        return danmakuStyle;
    }

    public void setDanmakuStyle(String danmakuStyle) {
        this.danmakuStyle = danmakuStyle;
    }

    public boolean isSilentMode() {
        return silentMode;
    }

    public void setSilentMode(boolean silentMode) {
        this.silentMode = silentMode;
    }
}
