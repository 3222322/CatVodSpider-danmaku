package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.github.catvod.net.OkHttp;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

public class Utils {

    private static WeakReference<Activity> cachedActivity;
    private static final Object PORT_LOCK = new Object();
    private static volatile int cachedHostPort;
    private static volatile long cachedHostPortAt;

    public static Activity getTopActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Map<Object, Object> activities;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                activities = (HashMap<Object, Object>) activitiesField.get(activityThread);
            } else {
                activities = (android.util.ArrayMap<Object, Object>) activitiesField.get(activityThread);
            }
            for (Object activityRecord : activities.values()) {
                Class<?> activityRecordClass = activityRecord.getClass();
                java.lang.reflect.Field pausedField = activityRecordClass.getDeclaredField("paused");
                pausedField.setAccessible(true);
                if (!pausedField.getBoolean(activityRecord)) {
                    java.lang.reflect.Field activityField = activityRecordClass.getDeclaredField("activity");
                    activityField.setAccessible(true);
                    Activity activity = (Activity) activityField.get(activityRecord);
                    if (activity != null) {
                        cachedActivity = new WeakReference<>(activity);
                        return activity;
                    }
                }
            }
        } catch (Exception e) {
            DanmakuSpider.log("获取TopActivity失败: " + e.getMessage());
        }

        // 如果反射获取失败，尝试从缓存返回
        if (cachedActivity != null) {
            Activity activity = cachedActivity.get();
            if (activity != null && !activity.isFinishing()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
                    return null;
                }
                return activity;
            }
        }
        return null;
    }

    /**
     * 检查是否处于静默模式
     */
    private static boolean isSilentMode(Activity activity) {
        DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
        return config != null && config.isSilentMode();
    }

    public static void safeShowToast(final Context context, final String message) {
        safeShowToast(context, message, false);
    }

    public static void safeShowToast(final Context context, final String message, boolean isForcedShow) {
        // 检查静默模式
        Activity activity = (context instanceof Activity) ? (Activity) context : null;
        if (isSilentMode(activity) && !isForcedShow) {
            DanmakuSpider.log("🔇 静默模式已开启，跳过 Toast 显示：" + message);
            return;
        }

        if (context instanceof Activity) {
            safeShowToast2((Activity) context, message, isForcedShow);
        } else {
            new Handler(Looper.getMainLooper()).post(() -> 
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            );
        }
    }

    public static void safeShowToast2(Activity activity, String message) {
        safeShowToast2(activity, message, false);
    }

    public static void safeShowToast2(Activity activity, String message, boolean isForcedShow) {
        // 检查静默模式
        DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
        if (config != null && config.isSilentMode() && !isForcedShow) {
            DanmakuSpider.log("🔇 静默模式已开启，跳过 Toast 显示：" + message);
            return;
        }
        if (activity != null && !activity.isFinishing()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                if (activity.isDestroyed()) return;
            }
            safeRunOnUiThread(activity, new Runnable() {
                @Override
                public void run() {
                    if (activity != null && !activity.isFinishing()) {
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    public static void safeRunOnUiThread(Activity activity, Runnable runnable) {
        if (activity != null && !activity.isFinishing()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                if (activity.isDestroyed()) return;
            }
            activity.runOnUiThread(runnable);
        }
    }

    public static int getPort() {
        int port = cachedHostPort;
        if (port > 0 && System.currentTimeMillis() - cachedHostPortAt < 5000) {
            return port;
        }
        synchronized (PORT_LOCK) {
            port = cachedHostPort;
            if (port > 0 && System.currentTimeMillis() - cachedHostPortAt < 5000) {
                return port;
            }
            int reflected = reflectedPort();
            if (isHostMediaPort(reflected)) {
                cachedHostPort = reflected;
                cachedHostPortAt = System.currentTimeMillis();
                return reflected;
            }
            for (int candidate = 9978; candidate <= 9999; candidate++) {
                if (candidate == reflected) continue;
                if (isHostMediaPort(candidate)) {
                    cachedHostPort = candidate;
                    cachedHostPortAt = System.currentTimeMillis();
                    DanmakuSpider.log("检测到宿主 WebServer 端口: " + candidate);
                    return candidate;
                }
            }
            cachedHostPort = reflected > 0 ? reflected : 9978;
            cachedHostPortAt = System.currentTimeMillis();
            return cachedHostPort;
        }
    }

    public static void invalidatePort() {
        synchronized (PORT_LOCK) {
            cachedHostPort = 0;
            cachedHostPortAt = 0;
        }
    }

    private static int reflectedPort() {
        try {
            Class<?> clz = Class.forName("com.github.catvod.Proxy");
            return (int) clz.getMethod("getPort").invoke(null);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean isHostMediaPort(int port) {
        if (port <= 0) return false;
        try {
            String body = OkHttp.string("http://127.0.0.1:" + port + "/media", 300);
            if (body == null) return false;
            String trimmed = body.trim();
            return trimmed.startsWith("{") && trimmed.endsWith("}");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
