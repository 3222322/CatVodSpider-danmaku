package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.github.catvod.net.OkHttp;
import com.github.catvod.spider.entity.Media;
import com.google.gson.Gson;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 章节与片头片尾管理。原生章节来自 FongMi 当前播放器，用户标记保存在插件本地。
 */
public final class ChapterManager {

    private static final String PREFS_NAME = "danmaku_chapter_data";
    private static final String KEY_PREFIX = "chapter_";
    private static final int AUTO_MARKER_VERSION = 2;
    private static final long AUTO_QUERY_RETRY_MS = 15000L;
    private static final long POSITION_GRACE_MS = 12000L;
    private static final long BACKWARD_JUMP_THRESHOLD_MS = 10000L;
    private static final Object AUTO_QUERY_LOCK = new Object();
    private static final Gson GSON = new Gson();
    private static final ScheduledExecutorService AUTO_SKIP_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "DanmakuChapterAutoSkip");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static volatile boolean autoSkipStarted;
    private static volatile String lastMediaKey = "";
    private static volatile String lastSkipType = "";
    private static volatile long lastSkipAt;
    private static volatile long lastSkipAttemptAt;
    private static volatile boolean introSkipDoneForMedia;
    private static volatile String observedMediaKey = "";
    private static volatile long observedPositionMs = -1;
    private static volatile long observedAtMs;
    private static volatile long observedMediaSince;
    private static volatile long suppressAutoSkipUntil;
    private static volatile MediaInfo lastPlaybackInfo;
    private static volatile String lastAutoQueryKey = "";
    private static volatile long lastAutoQueryAt;

    private ChapterManager() {
    }

    public static class Chapter {
        public String id;
        public String title;
        public long startMs;
        public boolean nativeChapter;

        public Chapter() {
        }

        public Chapter(String id, String title, long startMs, boolean nativeChapter) {
            this.id = id;
            this.title = title;
            this.startMs = Math.max(0, startMs);
            this.nativeChapter = nativeChapter;
        }
    }

    public static class ChapterData {
        public boolean autoIntro;
        public boolean autoEnding;
        public boolean manualIntroOverride;
        public boolean manualOutroOverride;
        public boolean autoIntroResolved;
        public boolean autoOutroResolved;
        public int autoMarkerVersion;
        public boolean nativeMarkerStateInitialized;
        public long introStartMs = -1;
        public long introEndMs = -1;
        public long outroStartMs = -1;
        public long recapStartMs = -1;
        public long recapEndMs = -1;
        public long nativeOpeningWrittenMs = -1;
        public long nativeEndingWrittenMs = -1;
        public List<Chapter> chapters = new ArrayList<>();
    }

    public static class MediaInfo {
        public String key;
        public String legacyKey;
        public String fallbackKey;
        public String title;
        public String artist;
        public String url;
        public long positionMs;
        public long durationMs;

        public String getDisplayTitle() {
            if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(artist)) return title + " · " + artist;
            if (!TextUtils.isEmpty(title)) return title;
            return TextUtils.isEmpty(artist) ? "当前视频" : artist;
        }
    }

    public static MediaInfo getMediaInfo() {
        try {
            String json = readMediaJson();
            if (TextUtils.isEmpty(json)) return getNativeMediaInfo();
            Media media = GSON.fromJson(json, Media.class);
            if (media == null || (TextUtils.isEmpty(media.getTitle())
                    && TextUtils.isEmpty(media.getArtist()))) {
                return getNativeMediaInfo();
            }
            MediaInfo info = fromMedia(media);
            if (info != null) lastPlaybackInfo = info;
            return info;
        } catch (Exception e) {
            DanmakuSpider.log("读取章节播放状态失败: " + e.getMessage());
            return null;
        }
    }

    private static MediaInfo getNativeMediaInfo() {
        MediaInfo info = FongMiNativeHelper.getMediaInfo(activityRef.get());
        if (info != null) lastPlaybackInfo = info;
        return info;
    }

    private static String readMediaJson() {
        String json = fetchMediaJson();
        if (isMediaJson(json)) return json;
        Utils.invalidatePort();
        json = fetchMediaJson();
        return isMediaJson(json) ? json : "";
    }

    private static String fetchMediaJson() {
        try {
            return OkHttp.string(
                    "http://127.0.0.1:" + Utils.getPort() + "/media", 2500);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isMediaJson(String json) {
        if (TextUtils.isEmpty(json)) return false;
        String trimmed = json.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}");
    }

    public static MediaInfo fromMedia(Media media) {
        if (media == null) return null;
        return fromPlaybackValues(media.getTitle(), media.getArtist(), media.getUrl(),
                media.getPosition(), media.getDuration());
    }

    public static MediaInfo fromPlaybackValues(
            String title, String artist, String url, long position, long duration) {
        MediaInfo info = new MediaInfo();
        info.title = nullToEmpty(title);
        info.artist = nullToEmpty(artist);
        info.url = nullToEmpty(url);
        info.positionMs = Math.max(0, position);
        info.durationMs = Math.max(0, duration);
        if (TextUtils.isEmpty(info.title) && TextUtils.isEmpty(info.artist)) return null;
        String identity = nullToEmpty(info.title) + "|" + nullToEmpty(info.artist);
        info.key = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        info.legacyKey = UUID.nameUUIDFromBytes(
                (identity + "|" + nullToEmpty(info.url))
                        .getBytes(StandardCharsets.UTF_8)).toString();
        info.fallbackKey = UUID.nameUUIDFromBytes(
                (identity + "|mediasession://current")
                        .getBytes(StandardCharsets.UTF_8)).toString();
        return info;
    }

    public static ChapterData loadData(Context context, MediaInfo media) {
        ChapterData data = null;
        boolean migrated = false;
        if (context != null && media != null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_PREFIX + media.key, null);
            if (TextUtils.isEmpty(json) && !TextUtils.equals(media.key, media.legacyKey)) {
                json = prefs.getString(KEY_PREFIX + media.legacyKey, null);
                migrated = !TextUtils.isEmpty(json);
            }
            if (TextUtils.isEmpty(json) && !TextUtils.equals(media.key, media.fallbackKey)
                    && !TextUtils.equals(media.legacyKey, media.fallbackKey)) {
                json = prefs.getString(KEY_PREFIX + media.fallbackKey, null);
                migrated = !TextUtils.isEmpty(json);
            }
            if (!TextUtils.isEmpty(json)) {
                try {
                    data = GSON.fromJson(json, ChapterData.class);
                } catch (Exception ignored) {
                }
            }
        }
        if (data == null) data = new ChapterData();
        if (data.chapters == null) data.chapters = new ArrayList<>();
        if (migrated && context != null && media != null) {
            saveData(context, media, data);
        }
        return data;
    }

    public static void saveData(Context context, MediaInfo media, ChapterData data) {
        if (context == null || media == null || data == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_PREFIX + media.key, GSON.toJson(data)).apply();
    }

    public static void setMarker(Context context, MediaInfo media, ChapterData data, String type, long positionMs) {
        if (data == null || media == null) return;
        long value = Math.max(0, positionMs);
        if ("introStart".equals(type)) {
            data.introStartMs = value;
            data.manualIntroOverride = true;
        }
        else if ("introEnd".equals(type)) {
            data.introEndMs = value;
            if (data.introStartMs < 0 || data.introStartMs >= value) data.introStartMs = 0;
            data.autoIntro = true;
            data.manualIntroOverride = true;
        } else if ("outroStart".equals(type)) {
            data.outroStartMs = value;
            data.autoEnding = true;
            data.manualOutroOverride = true;
        }
        saveData(context, media, data);
        syncNativeHistory(activityRef.get(), media, data);
    }

    public static void clearMarker(Context context, MediaInfo media, ChapterData data, String type) {
        if (data == null || media == null) return;
        if ("intro".equals(type) || "introEnd".equals(type) || "introStart".equals(type)) {
            data.introStartMs = -1;
            data.introEndMs = -1;
            data.autoIntro = false;
            data.manualIntroOverride = true;
        } else if ("outro".equals(type) || "outroStart".equals(type)) {
            data.outroStartMs = -1;
            data.autoEnding = false;
            data.manualOutroOverride = true;
        }
        saveData(context, media, data);
        syncNativeHistory(activityRef.get(), media, data);
    }

    public interface MarkerCallback {
        void onComplete(MediaInfo media, ChapterData data, boolean cleared);
    }

    public static void recordCurrentMarker(Activity activity, String type, MarkerCallback callback) {
        updateCurrentMarker(activity, type, false, callback);
    }

    public static void clearCurrentMarker(Activity activity, String type, MarkerCallback callback) {
        updateCurrentMarker(activity, type, true, callback);
    }

    private static void updateCurrentMarker(Activity activity, String type, boolean clear,
                                            MarkerCallback callback) {
        if (activity == null) return;
        new Thread(() -> {
            MediaInfo media = getMediaInfo();
            if (media == null) {
                activity.runOnUiThread(() -> Utils.safeShowToast(activity, "无法读取当前播放进度"));
                return;
            }
            ChapterData data = loadData(activity, media);
            if (clear) clearMarker(activity, media, data, type);
            else setMarker(activity, media, data, type, media.positionMs);
            activity.runOnUiThread(() -> {
                if (callback != null) callback.onComplete(media, data, clear);
                long marker = "intro".equals(type) || "introEnd".equals(type)
                        ? data.introEndMs : data.outroStartMs;
                Utils.safeShowToast(activity, clear ? "已清除标记"
                        : ("片头片尾标记已保存：" + formatTime(marker)));
            });
        }, "DanmakuManualMarker").start();
    }

    public static void addCustomChapter(Context context, MediaInfo media, ChapterData data, String title, long positionMs) {
        if (data == null || media == null) return;
        String name = TextUtils.isEmpty(title) ? "自定义章节" : title.trim();
        data.chapters.add(new Chapter(UUID.randomUUID().toString(), name, positionMs, false));
        Collections.sort(data.chapters, (left, right) -> Long.compare(left.startMs, right.startMs));
        saveData(context, media, data);
    }

    public static void deleteCustomChapter(Context context, MediaInfo media, ChapterData data, String id) {
        if (data == null) return;
        for (int i = data.chapters.size() - 1; i >= 0; i--) {
            if (TextUtils.equals(data.chapters.get(i).id, id)) data.chapters.remove(i);
        }
        saveData(context, media, data);
    }

    /**
     * 读取 FongMi 当前媒体章节。新版方法名为 getCurrentMediaChapters，5.5.x 混淆后为 I0。
     * 读取失败时返回空列表，不影响本地章节功能。
     */
    public static List<Chapter> getNativeChapters(Activity activity) {
        if (activity == null || activity.isFinishing()) return new ArrayList<>();
        try {
            Object player = findChapterPlayer(activity);
            if (player == null) return new ArrayList<>();
            List<?> raw = null;
            for (Method method : player.getClass().getMethods()) {
                if (method.getParameterTypes().length != 0) continue;
                if (!List.class.isAssignableFrom(method.getReturnType())) continue;
                String name = method.getName();
                if (!name.equals("I0") && !name.equals("getCurrentMediaChapters")
                        && !name.toLowerCase().contains("chapter")) continue;
                try {
                    Object value = method.invoke(player);
                    if (value instanceof List && !((List<?>) value).isEmpty()) {
                        raw = (List<?>) value;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (raw == null) return new ArrayList<>();
            List<Chapter> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object item : raw) {
                Chapter chapter = parseNativeChapter(item);
                if (chapter == null || chapter.startMs < 0) continue;
                String key = chapter.startMs + "|" + chapter.title;
                if (seen.add(key)) result.add(chapter);
            }
            Collections.sort(result, (left, right) -> Long.compare(left.startMs, right.startMs));
            return result;
        } catch (Throwable e) {
            DanmakuSpider.log("读取原生章节失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static boolean seekTo(long positionMs) {
        long target = Math.max(0, positionMs);
        if (FongMiNativeHelper.seekTo(target)) return true;
        return control("seek", target);
    }

    public static boolean seekToManual(long positionMs) {
        long target = Math.max(0, positionMs);
        long now = System.currentTimeMillis();
        suppressAutoSkipUntil = Math.max(suppressAutoSkipUntil, now + 10000);
        Activity activity = activityRef.get();
        MediaInfo media = lastPlaybackInfo;
        if (activity != null && media != null) {
            ChapterData data = loadData(activity, media);
            long endingBoundary = data.outroStartMs;
            if (endingBoundary > 0 && target >= endingBoundary) {
                lastSkipType = "ending";
            }
            syncNativeHistory(activity, media, data);
        }
        return seekTo(target);
    }

    public static boolean nextEpisode() {
        if (FongMiNativeHelper.nextEpisode()) return true;
        return control("next", 0);
    }

    private static boolean control(String type, long positionMs) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("type", type);
            if ("seek".equals(type)) params.put("position", String.valueOf(Math.max(0, positionMs)));
            String body = OkHttp.post("http://127.0.0.1:" + Utils.getPort() + "/remote", params);
            boolean ok = !TextUtils.isEmpty(body)
                    && !body.contains("Playback unavailable")
                    && !body.contains("Invalid control")
                    && !body.contains("Same origin required");
            if (!ok) DanmakuSpider.log("播放控制失败: " + type + " " + body);
            return ok;
        } catch (Exception e) {
            DanmakuSpider.log("播放控制异常: " + e.getMessage());
            return false;
        }
    }

    public static void startAutoSkip(Activity activity) {
        if (activity != null) activityRef = new WeakReference<>(activity);
        if (autoSkipStarted) return;
        autoSkipStarted = true;
        AUTO_SKIP_EXECUTOR.scheduleWithFixedDelay(ChapterManager::autoSkipTick, 500, 1000, TimeUnit.MILLISECONDS);
    }

    static Activity getCurrentActivity() {
        return activityRef.get();
    }

    public static MediaInfo getCachedMediaInfo() {
        return lastPlaybackInfo;
    }

    public static void requestAutoMarkers(Activity activity, Media media) {
        if (activity == null || media == null) return;
        DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
        if (config == null || !config.isAutoSkipEnabled()) return;
        MediaInfo info = fromMedia(media);
        if (info == null) return;
        lastPlaybackInfo = info;
        ChapterData data = loadData(activity, info);
        boolean needsData = needsAutoMarkerRefresh(data)
                || (!data.autoIntroResolved && data.introEndMs < 0 && !data.manualIntroOverride)
                || (!data.autoOutroResolved && data.outroStartMs < 0 && !data.manualOutroOverride);
        if (!needsData) return;
        requestAutoMarkersOnce(activity, info, config);
    }

    private static void autoSkipTick() {
        try {
            Activity activity = activityRef.get();
            if (activity == null || activity.isFinishing()) return;
            MediaInfo media = getMediaInfo();
            if (media == null || media.positionMs <= 0) return;
            lastPlaybackInfo = media;
            long now = System.currentTimeMillis();
            boolean manualJump = false;
            boolean manualBackwardJump = false;
            boolean inInitialPositionGrace = false;
            long previousPositionMs = observedPositionMs;
            if (!TextUtils.equals(observedMediaKey, media.key)) {
                observedMediaKey = media.key;
                observedPositionMs = media.positionMs;
                observedAtMs = now;
                observedMediaSince = now;
                suppressAutoSkipUntil = now + 10000;
            } else if (observedPositionMs >= 0 && observedAtMs > 0) {
                long elapsed = Math.max(0, now - observedAtMs);
                long delta = media.positionMs - observedPositionMs;
                long forwardAllowance = Math.max(12000, elapsed + 5000);
                inInitialPositionGrace = observedMediaSince > 0
                        && now - observedMediaSince < POSITION_GRACE_MS;
                if (delta < -BACKWARD_JUMP_THRESHOLD_MS || delta > forwardAllowance) {
                    manualJump = true;
                    manualBackwardJump = delta < -BACKWARD_JUMP_THRESHOLD_MS;
                    suppressAutoSkipUntil = now + 10000;
                    lastSkipType = "";
                    lastSkipAttemptAt = 0;
                    DanmakuSpider.log("检测到播放位置跳变: "
                            + formatTime(previousPositionMs) + " -> "
                            + formatTime(media.positionMs)
                            + "，10秒内暂停章节自动跳过");
                }
                observedPositionMs = media.positionMs;
                observedAtMs = now;
            }
            ChapterData data = loadData(activity, media);
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            boolean autoEnabled = config != null && config.isAutoSkipEnabled();
            FongMiNativeHelper.HistoryState nativeHistory =
                    FongMiNativeHelper.readHistory(activity);
            if (nativeHistory.available) {
                long nativeIntro = nativeHistory.opening;
                long nativeOutro = nativeEndingToOutroStart(
                        media.durationMs, nativeHistory.ending);
                boolean changed = false;
                boolean hasLocalIntro = data.introEndMs > 0
                        && (data.autoIntro || data.manualIntroOverride);
                boolean nativeIntroPluginOwned =
                        isPluginOwnedOpening(data, nativeIntro);
                boolean nativeIntroManual = nativeIntro > 0 && !nativeIntroPluginOwned
                        && (!data.manualIntroOverride
                        || Math.abs(nativeIntro - data.introEndMs) > 1000);
                if (nativeIntroManual) {
                    data.introStartMs = data.introStartMs >= 0 ? data.introStartMs : 0;
                    data.introEndMs = nativeIntro;
                    data.autoIntro = true;
                    data.manualIntroOverride = true;
                    changed = true;
                    DanmakuSpider.log("识别到原生手动片头: "
                            + formatTime(nativeIntro));
                } else if (!hasLocalIntro && nativeIntro <= 0
                        && (data.introStartMs >= 0 || data.introEndMs > 0 || data.autoIntro)) {
                    data.introStartMs = -1;
                    data.introEndMs = -1;
                    data.autoIntro = false;
                    changed = true;
                }
                boolean hasLocalOutro = data.outroStartMs > 0
                        && (data.autoEnding || data.manualOutroOverride);
                boolean nativeOutroPluginOwned = isPluginOwnedEnding(
                        data, media.durationMs, nativeHistory.ending);
                boolean nativeOutroManual = nativeOutro > 0
                        && !nativeOutroPluginOwned
                        && (!data.manualOutroOverride
                        || Math.abs(nativeOutro - data.outroStartMs) > 1000);
                long resolvedOutro = nativeOutroManual
                        ? (nativeOutro > 0 ? nativeOutro : -1)
                        : (hasLocalOutro ? data.outroStartMs : -1);
                boolean resolvedAutoEnding = resolvedOutro > 0;
                if (data.outroStartMs != resolvedOutro) {
                    data.outroStartMs = resolvedOutro;
                    changed = true;
                }
                if (data.autoEnding != resolvedAutoEnding) {
                    data.autoEnding = resolvedAutoEnding;
                    changed = true;
                }
                if (nativeOutroManual) {
                    if (!data.manualOutroOverride) {
                        data.manualOutroOverride = true;
                        changed = true;
                    }
                    DanmakuSpider.log("识别到原生手动片尾: "
                            + formatTime(nativeOutro));
                }
                if (changed) saveData(activity, media, data);
                if (autoEnabled) {
                    long expectedNativeEnding =
                            outroStartToNativeEnding(media.durationMs, data.outroStartMs);
                    boolean nativeSynced = syncNativeHistory(activity, media, data);
                    if (nativeHistory.ending != expectedNativeEnding && nativeSynced) {
                        DanmakuSpider.log("修正原生片尾剩余时间: "
                                + formatTime(nativeHistory.ending) + " -> "
                                + formatTime(expectedNativeEnding)
                                + "，绝对时间=" + formatTime(data.outroStartMs));
                    }
                } else {
                    clearPluginOwnedNativeMarkers(
                            activity, media, data, nativeHistory);
                }
            }
            if (!autoEnabled) return;
            boolean autoIntroEnabled = data.autoIntro;
            boolean autoEndingEnabled = data.autoEnding;
            String mediaKey = media.key;
            if (!TextUtils.equals(lastMediaKey, mediaKey)) {
                lastMediaKey = mediaKey;
                lastSkipType = "";
                lastSkipAt = 0;
                lastSkipAttemptAt = 0;
                introSkipDoneForMedia = false;
            }
            if (manualBackwardJump && !inInitialPositionGrace
                    && isManualPullbackIntoIntro(
                    previousPositionMs, media.positionMs, data)) {
                introSkipDoneForMedia = true;
                DanmakuSpider.log("检测到手动拉回片头区域: "
                        + formatTime(previousPositionMs) + " -> "
                        + formatTime(media.positionMs)
                        + "，本集不再自动跳过片头");
            }
            if (manualJump && data.outroStartMs > 0
                    && media.positionMs >= data.outroStartMs) {
                lastSkipType = "ending";
            }

            boolean needsAutoData = needsAutoMarkerRefresh(data)
                    || (!data.autoIntroResolved && data.introEndMs < 0 && !data.manualIntroOverride)
                    || (!data.autoOutroResolved && data.outroStartMs < 0 && !data.manualOutroOverride);
            if (needsAutoData) {
                requestAutoMarkersOnce(activity, media, config);
            }
            if (now < suppressAutoSkipUntil) return;
            if (!introSkipDoneForMedia && autoIntroEnabled
                    && data.introStartMs >= 0 && data.introEndMs > data.introStartMs
                    && media.positionMs >= data.introStartMs && media.positionMs < data.introEndMs) {
                if (now - lastSkipAttemptAt >= 3000
                        && (!"intro".equals(lastSkipType) || now - lastSkipAt > 3000)) {
                    lastSkipAttemptAt = now;
                    if (seekTo(data.introEndMs)) {
                        lastSkipType = "intro";
                        lastSkipAt = now;
                        introSkipDoneForMedia = true;
                        DanmakuSpider.log("自动跳过片头: " + data.introStartMs + " -> " + data.introEndMs);
                    }
                }
                return;
            }
            if ("intro".equals(lastSkipType)
                    && media.positionMs > data.introEndMs + 5000) {
                lastSkipType = "";
            }

            if (autoEndingEnabled && data.outroStartMs >= 0 && media.positionMs >= data.outroStartMs) {
                if (!"ending".equals(lastSkipType)) {
                    boolean handled = nextEpisode();
                    if (!handled && media.durationMs > 0) handled = seekTo(media.durationMs);
                    if (handled) {
                        lastSkipType = "ending";
                        lastSkipAt = now;
                        DanmakuSpider.log("自动跳过片尾: " + data.outroStartMs);
                    }
                }
            }
        } catch (Throwable e) {
            DanmakuSpider.log("章节自动跳过异常: " + e.getMessage());
        }
    }

    private static boolean isManualPullbackIntoIntro(
            long previousPositionMs, long currentPositionMs, ChapterData data) {
        if (data == null || data.introStartMs < 0 || data.introEndMs <= data.introStartMs) {
            return false;
        }
        return previousPositionMs >= data.introStartMs
                && currentPositionMs >= data.introStartMs
                && currentPositionMs < data.introEndMs;
    }

    private static void applyAutoMarkers(Activity activity, MediaInfo media,
                                         AutoSkipService.Markers markers) {
        if (activity == null || media == null || markers == null) return;
        if (!markers.hasAny()) {
            if (!TextUtils.isEmpty(markers.error)) {
                DanmakuSpider.log("Auto 片头查询: " + markers.error);
            }
            return;
        }
        MediaInfo current = lastPlaybackInfo;
        if (!isSamePlayback(current, media)) {
            DanmakuSpider.log("Auto 忽略过期集标记: " + media.getDisplayTitle());
            return;
        }
        ChapterData data = loadData(activity, current);
        boolean changed = false;
        boolean markersChanged = false;
        if (markers.hasIntro() && !data.manualIntroOverride) {
            long introStart = Math.max(0, markers.introStartMs);
            if (data.introStartMs != introStart || data.introEndMs != markers.introEndMs
                    || !data.autoIntro) {
                data.introStartMs = introStart;
                data.introEndMs = markers.introEndMs;
                data.autoIntro = true;
                changed = true;
                markersChanged = true;
            }
        }
        if (markers.hasOutro() && !data.manualOutroOverride) {
            if (data.outroStartMs != markers.outroStartMs || !data.autoEnding) {
                data.outroStartMs = markers.outroStartMs;
                data.autoEnding = true;
                changed = true;
                markersChanged = true;
            }
        }
        if (markers.hasRecap()) {
            if (data.recapStartMs != markers.recapStartMs
                    || data.recapEndMs != markers.recapEndMs) {
                data.recapStartMs = markers.recapStartMs;
                data.recapEndMs = markers.recapEndMs;
                changed = true;
                markersChanged = true;
            }
        }
        if (!data.manualIntroOverride && !data.autoIntroResolved) {
            data.autoIntroResolved = true;
            changed = true;
        }
        if (!data.manualOutroOverride && !data.autoOutroResolved) {
            data.autoOutroResolved = true;
            changed = true;
        }
        if (data.autoMarkerVersion < AUTO_MARKER_VERSION) {
            data.autoMarkerVersion = AUTO_MARKER_VERSION;
            changed = true;
        }
        if (!changed) return;
        saveData(activity, current, data);
        boolean nativeWritten = syncNativeHistory(activity, current, data);
        if (markersChanged) {
            DanmakuSpider.log("Auto 已写入片头片尾: source=" + markers.source
                    + " intro=" + formatTime(data.introStartMs) + "->" + formatTime(data.introEndMs)
                    + " outro=" + formatTime(data.outroStartMs)
                    + " recap=" + formatTime(data.recapStartMs) + "->" + formatTime(data.recapEndMs)
                    + " imdb=" + markers.imdbId + " native=" + nativeWritten);
        }
    }

    private static boolean syncNativeHistory(Activity activity, MediaInfo media,
                                             ChapterData data) {
        if (activity == null || media == null || data == null) return false;
        FongMiNativeHelper.HistoryState nativeHistory =
                FongMiNativeHelper.readHistory(activity);
        boolean controlOpening = data.manualIntroOverride
                || isPluginOwnedOpening(data, nativeHistory.opening);
        boolean controlEnding = data.manualOutroOverride
                || isPluginOwnedEnding(data, media.durationMs, nativeHistory.ending);
        if (!controlOpening && !controlEnding) return false;
        long opening = controlOpening
                ? (data.manualIntroOverride && data.introEndMs > 0
                ? data.introEndMs : 0)
                : nativeHistory.opening;
        long ending = controlEnding
                ? (data.manualOutroOverride
                ? outroStartToNativeEnding(media.durationMs, data.outroStartMs) : 0)
                : nativeHistory.ending;
        boolean written = FongMiNativeHelper.writeHistory(activity, opening, ending);
        if (written) {
            boolean fingerprintChanged = !data.nativeMarkerStateInitialized
                    || data.nativeOpeningWrittenMs
                    != (controlOpening ? opening : -1)
                    || data.nativeEndingWrittenMs
                    != (controlEnding ? ending : -1);
            data.nativeMarkerStateInitialized = true;
            data.nativeOpeningWrittenMs = controlOpening ? opening : -1;
            data.nativeEndingWrittenMs = controlEnding ? ending : -1;
            if (fingerprintChanged) saveData(activity, media, data);
        }
        return written;
    }

    private static boolean needsAutoMarkerRefresh(ChapterData data) {
        return data != null && data.autoMarkerVersion < AUTO_MARKER_VERSION
                && (!data.manualIntroOverride || !data.manualOutroOverride);
    }

    private static void requestAutoMarkersOnce(
            Activity activity, MediaInfo media, DanmakuConfig config) {
        if (activity == null || media == null || config == null) return;
        String key = media.key;
        long now = System.currentTimeMillis();
        synchronized (AUTO_QUERY_LOCK) {
            if (TextUtils.equals(lastAutoQueryKey, key)
                    && now - lastAutoQueryAt < AUTO_QUERY_RETRY_MS) {
                return;
            }
        }
        boolean started = AutoSkipService.refreshAsync(activity, media, config,
                markers -> applyAutoMarkers(activity, media, markers));
        if (started) {
            synchronized (AUTO_QUERY_LOCK) {
                lastAutoQueryKey = key;
                lastAutoQueryAt = now;
            }
        }
    }

    private static void clearPluginOwnedNativeMarkers(
            Activity activity, MediaInfo media, ChapterData data,
            FongMiNativeHelper.HistoryState nativeHistory) {
        if (activity == null || media == null || data == null
                || nativeHistory == null || !nativeHistory.available) {
            return;
        }
        boolean clearOpening = isPluginOwnedOpening(data, nativeHistory.opening);
        boolean clearEnding = isPluginOwnedEnding(
                data, media.durationMs, nativeHistory.ending);
        if (!clearOpening && !clearEnding) return;
        long opening = clearOpening ? 0 : nativeHistory.opening;
        long ending = clearEnding ? 0 : nativeHistory.ending;
        if (!FongMiNativeHelper.writeHistory(activity, opening, ending)) return;
        if (clearOpening) data.nativeOpeningWrittenMs = 0;
        if (clearEnding) data.nativeEndingWrittenMs = 0;
        data.nativeMarkerStateInitialized = true;
        saveData(activity, media, data);
        DanmakuSpider.log("关闭 Auto，清除插件原生标记: opening="
                + (clearOpening ? "是" : "否") + " ending="
                + (clearEnding ? "是" : "否"));
    }

    private static boolean isPluginOwnedOpening(ChapterData data, long nativeOpening) {
        if (data == null || data.manualIntroOverride || data.introEndMs <= 0) {
            return false;
        }
        return nativeOpening > 0 && (nativeOpening == data.introEndMs
                || (data.nativeMarkerStateInitialized
                && nativeOpening == data.nativeOpeningWrittenMs));
    }

    private static boolean isPluginOwnedEnding(
            ChapterData data, long durationMs, long nativeEnding) {
        if (data == null || data.manualOutroOverride || data.outroStartMs <= 0) {
            return false;
        }
        long expected = outroStartToNativeEnding(durationMs, data.outroStartMs);
        return nativeEnding > 0 && expected > 0 && (nativeEnding == expected
                || (data.nativeMarkerStateInitialized
                && nativeEnding == data.nativeEndingWrittenMs));
    }

    private static long nativeEndingToOutroStart(long durationMs, long endingMs) {
        if (durationMs <= 0 || endingMs <= 0 || endingMs >= durationMs) return -1;
        return durationMs - endingMs;
    }

    private static long outroStartToNativeEnding(long durationMs, long outroStartMs) {
        if (durationMs <= 0 || outroStartMs <= 0 || outroStartMs >= durationMs) return 0;
        return durationMs - outroStartMs;
    }

    private static boolean isSamePlayback(MediaInfo current, MediaInfo target) {
        if (current == null || target == null) return false;
        return TextUtils.equals(playbackTitle(current), playbackTitle(target))
                && TextUtils.equals(playbackArtist(current), playbackArtist(target));
    }

    private static String playbackTitle(MediaInfo media) {
        return nullToEmpty(media.title).trim();
    }

    private static String playbackArtist(MediaInfo media) {
        String artist = nullToEmpty(media.artist).trim();
        return artist.isEmpty() ? playbackTitle(media) : artist;
    }

    public static String formatTime(long ms) {
        if (ms < 0) return "--:--";
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    private static Object findChapterPlayer(Activity activity) {
        Object service = findFieldByTypeName(activity, "PlaybackService");
        Object start = service != null ? service : activity;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return findChapterPlayer(start, 0, visited, 0);
    }

    private static Object findChapterPlayer(Object object, int depth, Set<Object> visited, int count) {
        if (object == null || depth > 4 || count > 120 || visited.contains(object)) return null;
        visited.add(object);
        Class<?> type = object.getClass();
        if (isSimpleType(type)) return null;
        if (hasChapterMethod(type)) return object;
        int nextCount = count;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Class<?> fieldType = field.getType();
                if (fieldType.isPrimitive() || isSimpleType(fieldType)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value == null || visited.contains(value)) continue;
                    Object found = findChapterPlayer(value, depth + 1, visited, ++nextCount);
                    if (found != null) return found;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean hasChapterMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getParameterTypes().length != 0) continue;
            if (!List.class.isAssignableFrom(method.getReturnType())) continue;
            String name = method.getName();
            if (name.equals("I0") || name.equals("getCurrentMediaChapters")
                    || name.toLowerCase().contains("chapter")) return true;
        }
        return false;
    }

    private static Object findFieldByTypeName(Object owner, String typeName) {
        if (owner == null) return null;
        for (Class<?> current = owner.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value != null && value.getClass().getName().contains(typeName)) return value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Chapter parseNativeChapter(Object item) {
        if (item == null || isSimpleType(item.getClass())) return null;
        long time = Long.MIN_VALUE;
        String title = null;
        int index = -1;
        for (Class<?> current = item.getClass(); current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(item);
                    if (value instanceof Long && (Long) value >= 0 && time == Long.MIN_VALUE) time = (Long) value;
                    else if (value instanceof String && title == null) title = (String) value;
                    else if (value instanceof Integer && index < 0) index = (Integer) value;
                } catch (Throwable ignored) {
                }
            }
        }
        if (time == Long.MIN_VALUE || TextUtils.isEmpty(title)) return null;
        long startMs = time;
        if (startMs > 10_000_000L) startMs /= 1000;
        return new Chapter("native-" + index + "-" + startMs, title, startMs, true);
    }

    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive() || type.isEnum()
                || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class
                || type.getName().startsWith("java.util.")
                || type.getName().startsWith("android.view.")
                || type.getName().startsWith("android.content.")
                || type.getName().startsWith("android.os.");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
