package com.github.catvod.spider;

import android.app.Activity;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import com.github.catvod.spider.entity.Media;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * FongMi/OK影视当前版本的反射桥接，避免依赖播放器混淆后的类名。
 */
final class FongMiNativeHelper {

    private FongMiNativeHelper() {
    }

    static class HistoryState {
        boolean available;
        long opening;
        long ending;
    }

    static HistoryState readHistory(Activity activity) {
        HistoryState state = new HistoryState();
        if (activity == null) return state;
        Object direct = findDirectFieldByTypeName(activity,
                "com.fongmi.android.tv.bean.History");
        List<Object> histories = direct != null
                ? Collections.singletonList(direct)
                : findObjectsByTypeName(activity, "com.fongmi.android.tv.bean.History", 4);
        for (Object history : histories) {
            try {
                long opening = getLong(history, "getOpening");
                long ending = getLong(history, "getEnding");
                if (opening > state.opening) state.opening = opening;
                if (ending > state.ending) state.ending = ending;
                state.available = true;
            } catch (Throwable ignored) {
            }
        }
        return state;
    }

    static boolean writeHistory(Activity activity, long opening, long ending) {
        if (activity == null) return false;
        boolean changed = false;
        Object direct = findDirectFieldByTypeName(activity,
                "com.fongmi.android.tv.bean.History");
        List<Object> histories = direct != null
                ? Collections.singletonList(direct)
                : findObjectsByTypeName(activity, "com.fongmi.android.tv.bean.History", 4);
        for (Object history : histories) {
            try {
                long normalizedOpening = Math.max(0, opening);
                long normalizedEnding = Math.max(0, ending);
                long currentOpening = getLong(history, "getOpening");
                long currentEnding = getLong(history, "getEnding");
                if (currentOpening != normalizedOpening || currentEnding != normalizedEnding) {
                    history.getClass().getMethod("setOpening", long.class)
                            .invoke(history, normalizedOpening);
                    history.getClass().getMethod("setEnding", long.class)
                            .invoke(history, normalizedEnding);
                    history.getClass().getMethod("save").invoke(history);
                }
                changed = true;
            } catch (Throwable ignored) {
            }
        }
        return changed;
    }

    private static Object findDirectFieldByTypeName(Object owner, String typeName) {
        if (owner == null) return null;
        for (Class<?> current = owner.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().getName().equals(typeName)) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value != null) return value;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    static boolean seekTo(long positionMs) {
        final long target = Math.max(0, positionMs);
        return runOnMainThread(() -> {
            if (seekDirect(target)) return true;
            MediaController.TransportControls controls = getTransportControls();
            if (controls == null) return false;
            try {
                controls.seekTo(target);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        });
    }

    private static boolean seekDirect(long positionMs) {
        try {
            Class<?> service = Class.forName("com.fongmi.android.tv.service.PlaybackService");
            Object player = service.getField("i").get(null);
            if (player == null) return false;
            Method fallback = null;
            for (Method method : player.getClass().getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0] == long.class
                        && method.getReturnType() == void.class) {
                    String name = method.getName();
                    if (name.equals("i0") || name.equals("seekTo")
                            || name.toLowerCase().contains("seek")) {
                        method.invoke(player, Math.max(0, positionMs));
                        return true;
                    }
                    if (fallback == null) fallback = method;
                }
            }
            if (fallback != null) {
                fallback.invoke(player, Math.max(0, positionMs));
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static boolean nextEpisode() {
        return runOnMainThread(() -> {
            MediaController.TransportControls controls = getTransportControls();
            if (controls == null) return false;
            try {
                controls.skipToNext();
                return true;
            } catch (Throwable e) {
                return false;
            }
        });
    }

    static ChapterManager.MediaInfo getMediaInfo(Activity activity) {
        Media media = getMedia(activity);
        return media == null ? null : ChapterManager.fromMedia(media);
    }

    static Media getMedia(Activity activity) {
        MediaController controller = getMediaController(activity);
        if (controller == null) return null;
        try {
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            if (metadata == null) return null;
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String url = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            long position = state == null ? 0 : state.getPosition();
            if (title == null && artist == null) return null;
            Media media = new Media();
            media.setTitle(title);
            media.setArtist(artist);
            media.setUrl(url == null || url.isEmpty()
                    ? "mediasession://current" : url);
            media.setDuration(duration);
            media.setPosition(position);
            media.setState(state == null ? 3 : state.getState());
            return media;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private interface MainAction {
        boolean run();
    }

    private static boolean runOnMainThread(MainAction action) {
        if (action == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return action.run();
        final boolean[] result = {false};
        CountDownLatch latch = new CountDownLatch(1);
        boolean posted = new Handler(Looper.getMainLooper()).post(() -> {
            try {
                result[0] = action.run();
            } finally {
                latch.countDown();
            }
        });
        if (!posted) return false;
        try {
            if (!latch.await(1500, TimeUnit.MILLISECONDS)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result[0];
    }

    private static MediaController.TransportControls getTransportControls() {
        MediaController controller = getMediaController(ChapterManagerActivityHolder.get());
        return controller == null ? null : controller.getTransportControls();
    }

    private static MediaController getMediaController(Activity activity) {
        if (activity == null) return null;
        Object playbackService = findFieldByTypeName(activity, "PlaybackService");
        Object session = playbackService == null ? null
                : findObjectByType(playbackService, MediaSession.class, 0,
                Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        if (!(session instanceof MediaSession)) {
            session = findObjectByType(activity, MediaSession.class, 0,
                    Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        }
        if (!(session instanceof MediaSession)) return null;
        try {
            return ((MediaSession) session).getController();
        } catch (Throwable e) {
            return null;
        }
    }

    private static long getLong(Object target, String methodName) throws Exception {
        Object value = target.getClass().getMethod(methodName).invoke(target);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static List<Object> findObjectsByTypeName(Object owner, String typeName, int maxDepth) {
        List<Object> result = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectObjectsByTypeName(owner, typeName, 0, maxDepth, visited, result, 0);
        return result;
    }

    private static int collectObjectsByTypeName(Object object, String typeName, int depth,
                                                int maxDepth, Set<Object> visited,
                                                List<Object> result, int count) {
        if (object == null || depth > maxDepth || count > 240 || visited.contains(object)) {
            return count;
        }
        visited.add(object);
        Class<?> type = object.getClass();
        if (type.getName().equals(typeName)) {
            result.add(object);
            return count + 1;
        }
        if (isSimpleType(type)) return count;
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (isSimpleType(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value == null || visited.contains(value)) continue;
                    count = collectObjectsByTypeName(value, typeName, depth + 1, maxDepth,
                            visited, result, count);
                    if (count > 240) return count;
                } catch (Throwable ignored) {
                }
            }
        }
        return count;
    }

    private static Object findFieldByTypeName(Object owner, String typeName) {
        if (owner == null) return null;
        for (Class<?> current = owner.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value != null && value.getClass().getName().contains(typeName)) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Object findObjectByType(Object object, Class<?> targetType, int depth,
                                           Set<Object> visited, int count) {
        if (object == null || depth > 7 || count > 320 || visited.contains(object)) return null;
        visited.add(object);
        Class<?> type = object.getClass();
        if (targetType.isInstance(object)) return object;
        if (isSimpleType(type)) return null;
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                if (isSimpleType(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value == null || visited.contains(value)) continue;
                    Object found = findObjectByType(value, targetType, depth + 1,
                            visited, count + 1);
                    if (found != null) return found;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean isSimpleType(Class<?> type) {
        return type.isPrimitive() || type.isEnum()
                || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class
                || type.getName().startsWith("java.util.")
                || type.getName().startsWith("android.view.")
                || type.getName().startsWith("android.content.")
                || type.getName().startsWith("android.os.")
                || type.getName().startsWith("android.media.session.MediaController$");
    }

    private static final class ChapterManagerActivityHolder {
        static Activity get() {
            return ChapterManager.getCurrentActivity();
        }
    }
}
