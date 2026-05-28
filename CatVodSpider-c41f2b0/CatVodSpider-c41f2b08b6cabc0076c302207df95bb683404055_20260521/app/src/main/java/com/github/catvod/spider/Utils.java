package com.github.catvod.spider;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

public class Utils {

    private static WeakReference<Activity> cachedActivity;
    private static boolean callbacksRegistered = false;
    private static final Object callbacksLock = new Object();

    private static final Application.ActivityLifecycleCallbacks lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            cachedActivity = new WeakReference<>(activity);
        }
        @Override public void onActivityStarted(Activity activity) {
            cachedActivity = new WeakReference<>(activity);
        }
        @Override public void onActivityResumed(Activity activity) {
            cachedActivity = new WeakReference<>(activity);
        }
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
        @Override public void onActivityDestroyed(Activity activity) {
            if (cachedActivity != null && cachedActivity.get() == activity) {
                cachedActivity = null;
            }
        }
    };

    private static void ensureCallbacksRegistered(Activity activity) {
        if (callbacksRegistered) return;
        synchronized (callbacksLock) {
            if (callbacksRegistered) return;
            try {
                android.app.Application app = activity.getApplication();
                app.registerActivityLifecycleCallbacks(lifecycleCallbacks);
                callbacksRegistered = true;
            } catch (Exception e) {
                DanmakuSpider.log("❌ 注册ActivityLifecycleCallbacks失败: " + e.getMessage());
            }
        }
    }

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
                        ensureCallbacksRegistered(activity);
                        return activity;
                    }
                }
            }
        } catch (Exception e) {
            DanmakuSpider.log("获取TopActivity失败: " + e.getMessage());
        }

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

    private static boolean isSilentMode(Activity activity) {
        DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
        return config != null && config.isSilentMode();
    }

    public static void safeShowToast(final Context context, final String message) {
        safeShowToast(context, message, false);
    }

    public static void safeShowToast(final Context context, final String message, boolean isForcedShow) {
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
        Class<?> clz = null;
        int port = 9978;
        try {
            clz = Class.forName("com.github.catvod.Proxy");
            port = (int) clz.getMethod("getPort").invoke(null);
        } catch (Exception e) {
            DanmakuSpider.log("❌ 获取代理端口异常: " + e.getMessage());
        }
        return port;
    }
}
