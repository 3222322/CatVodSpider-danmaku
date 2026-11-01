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
    private static volatile long suppressAutoSkipUntil;
    private static volatile MediaInfo lastPlaybackInfo;

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
        public long introStartMs = -1;
        public long introEndMs = -1;
        public long outroStartMs = -1;
        public List<Chapter> chapters = new ArrayList<>();
    }

    public static class MediaInfo {
        public String key;
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
            String json = OkHttp.string("http://127.0.0.1:" + Utils.getPort() + "/media", 2500);
            if (TextUtils.isEmpty(json) || json.trim().equals("{}")) return null;
            Media media = GSON.fromJson(json, Media.class);
            if (media == null) return null;
            MediaInfo info = fromMedia(media);
            if (info != null) lastPlaybackInfo = info;
            return info;
        } catch (Exception e) {
            DanmakuSpider.log("读取章节播放状态失败: " + e.getMessage());
            return null;
        }
    }

    public static MediaInfo fromMedia(Media media) {
        if (media == null) return null;
        MediaInfo info = new MediaInfo();
        info.title = media.getTitle();
        info.artist = media.getArtist();
        info.url = media.getUrl();
        info.positionMs = Math.max(0, media.getPosition());
        info.durationMs = Math.max(0, media.getDuration());
        String identity = nullToEmpty(info.title) + "|" + nullToEmpty(info.artist)
                + "|" + nullToEmpty(info.url);
        info.key = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        return info;
    }

    public static ChapterData loadData(Context context, MediaInfo media) {
        ChapterData data = null;
        if (context != null && media != null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_PREFIX + media.key, null);
            if (!TextUtils.isEmpty(json)) {
                try {
                    data = GSON.fromJson(json, ChapterData.class);
                } catch (Exception ignored) {
                }
            }
        }
        if (data == null) data = new ChapterData();
        if (data.chapters == null) data.chapters = new ArrayList<>();
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
        boolean needsData = (!data.autoIntroResolved && data.introEndMs < 0 && !data.manualIntroOverride)
                || (!data.autoOutroResolved && data.outroStartMs < 0 && !data.manualOutroOverride);
        if (!needsData) return;
        AutoSkipService.refreshAsync(activity, info, config,
                markers -> applyAutoMarkers(activity, info, markers));
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
            if (!TextUtils.equals(observedMediaKey, media.key)) {
                observedMediaKey = media.key;
                observedPositionMs = media.positionMs;
                observedAtMs = now;
                suppressAutoSkipUntil = now + 10000;
            } else if (observedPositionMs >= 0 && observedAtMs > 0) {
                long elapsed = Math.max(0, now - observedAtMs);
                long delta = media.positionMs - observedPositionMs;
                long forwardAllowance = Math.max(12000, elapsed + 5000);
                if (delta < -4000 || delta > forwardAllowance) {
                    manualJump = true;
                    suppressAutoSkipUntil = now + 10000;
                    lastSkipType = "";
                    lastSkipAttemptAt = 0;
                    if (delta < -4000) introSkipDoneForMedia = true;
                    DanmakuSpider.log("检测到手动进度跳转，10秒内暂停章节自动跳过");
                }
                observedPositionMs = media.positionMs;
                observedAtMs = now;
            }
            ChapterData data = loadData(activity, media);
            FongMiNativeHelper.HistoryState nativeHistory =
                    FongMiNativeHelper.readHistory(activity);
            if (nativeHistory.available) {
                long nativeIntro = nativeHistory.opening;
                long nativeOutro = nativeHistory.ending;
                boolean keepColdOpenAutoIntro = data.autoIntro && data.autoIntroResolved
                        && data.introStartMs > 0 && !data.manualIntroOverride;
                boolean changed = data.outroStartMs != nativeOutro;
                if (!keepColdOpenAutoIntro) {
                    changed = changed || data.introEndMs != nativeIntro;
                    data.introStartMs = nativeIntro > 0 ? 0 : -1;
                    data.introEndMs = nativeIntro > 0 ? nativeIntro : -1;
                    data.autoIntro = nativeIntro > 0;
                }
                data.outroStartMs = nativeOutro > 0 ? nativeOutro : -1;
                data.autoEnding = nativeOutro > 0;
                if (changed) saveData(activity, media, data);
            }
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            if (config == null || !config.isAutoSkipEnabled()) return;
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
            if (manualJump && data.outroStartMs > 0
                    && media.positionMs >= data.outroStartMs) {
                lastSkipType = "ending";
            }

            boolean needsAutoData = (!data.autoIntroResolved && data.introEndMs < 0 && !data.manualIntroOverride)
                    || (!data.autoOutroResolved && data.outroStartMs < 0 && !data.manualOutroOverride);
            if (needsAutoData) {
                AutoSkipService.refreshAsync(activity, media, config,
                        markers -> applyAutoMarkers(activity, media, markers));
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
        if (!data.manualIntroOverride && !data.autoIntroResolved) {
            data.autoIntroResolved = true;
            changed = true;
        }
        if (!data.manualOutroOverride && !data.autoOutroResolved) {
            data.autoOutroResolved = true;
            changed = true;
        }
        if (!changed) return;
        saveData(activity, current, data);
        long nativeOpening = data.introStartMs <= 0 ? data.introEndMs : 0;
        boolean nativeWritten = FongMiNativeHelper.writeHistory(activity,
                nativeOpening > 0 ? nativeOpening : 0,
                data.outroStartMs > 0 ? data.outroStartMs : 0);
        if (markersChanged) {
            DanmakuSpider.log("Auto 已写入片头片尾: source=" + markers.source
                    + " intro=" + formatTime(data.introStartMs) + "->" + formatTime(data.introEndMs)
                    + " outro=" + formatTime(data.outroStartMs)
                    + " imdb=" + markers.imdbId + " native=" + nativeWritten);
        }
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
