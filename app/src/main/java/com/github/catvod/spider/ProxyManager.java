package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Timer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ProxyManager {

    public static final int PROXY_TYPE_NONE = 0;
    public static final int PROXY_TYPE_GO = 1;
    public static final int PROXY_TYPE_JAVA = 2;

    public static final int PROXY_PORT = 5575;
    public static final int JAVA_BACKEND_PORT = 5577;
    private static final String HEALTH_CHECK_URL = "http://127.0.0.1:" + PROXY_PORT + "/health";
    private static final String JAVA_HEALTH_CHECK_URL = "http://127.0.0.1:" + JAVA_BACKEND_PORT + "/health";

    private static final ExecutorService executor = Executors.newFixedThreadPool(5);
    private static final AtomicBoolean isProxyRunning = new AtomicBoolean(false);
    private static final AtomicInteger activeProxyType = new AtomicInteger(PROXY_TYPE_NONE);

    private static volatile int preferredProxyType = PROXY_TYPE_NONE;
    private static volatile JavaProxyServer javaProxyServer;
    private static volatile ProxyRelayServer relayServer;

    private static final List<String> proxyLogBuffer = new CopyOnWriteArrayList<>();
    private static final int MAX_LOG_SIZE = 1000;

    private static Timer healthCheckTimer;
    private static final Object healthCheckLock = new Object();
    private static long lastSuccessTime = 0;
    private static final long RESTART_DELAY_THRESHOLD = 10000;

    private static final AtomicBoolean isSwitching = new AtomicBoolean(false);
    private static final Object switchLock = new Object();

    public static boolean isSwitching() {
        return isSwitching.get();
    }

    public static void initialize(Context context) {
        ensureRelayServer();
        int saved = DanmakuConfigManager.loadConfig(context).getProxyType();
        preferredProxyType = saved;

        if (GoProxyManager.isGoProxyAssetExists() && preferredProxyType == PROXY_TYPE_GO) {
            boolean goStarted = startGoProxySync(context);
            if (goStarted) {
                activeProxyType.set(PROXY_TYPE_GO);
                isProxyRunning.set(true);
                log("[初始化] Go代理启动成功，后端端口: " + GoProxyManager.getCurrentBackendPort());
                startHealthCheck(context);
                return;
            }
            GoProxyManager.killGoProxy();
            log("[降级] Go代理启动失败，自动切换到Java代理");
        }

        if (GoProxyManager.isGoProxyAssetExists() && preferredProxyType != PROXY_TYPE_GO) {
            log("[初始化] 跳过SO代理自动启动，默认使用Java代理");
        }
        startJavaProxy(context);
    }

    private static boolean startGoProxySync(Context context) {
        final AtomicBoolean result = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        final int sessionId = GoProxyManager.beginStartSession();

        GoProxyManager.execute(() -> {
            try {
                GoProxyManager.startGoProxyOnceSync(context, sessionId);
                result.set(GoProxyManager.isProxyRunning.get());
            } catch (Exception e) {
                log("[Go代理] 同步启动异常: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("[Go代理] 同步启动等待中断");
            GoProxyManager.cancelStartSession(sessionId);
        }
        if (!result.get()) GoProxyManager.cancelStartSession(sessionId);
        return result.get();
    }

    public static void switchToGoProxy(final Context context) {
        if (!tryAcquireSwitch()) return;

        executor.execute(() -> {
            try {
                log("[切换] → Go代理");
                stopHealthCheck();
                stopJavaProxy();
                stopRelayServer();

                boolean goStarted = startGoProxySync(context.getApplicationContext());
                if (goStarted) {
                    activeProxyType.set(PROXY_TYPE_GO);
                    isProxyRunning.set(true);
                    log("[切换] Go代理启动成功，后端端口: " + GoProxyManager.getCurrentBackendPort());
                    startHealthCheck(context.getApplicationContext());
                    saveProxyType(context.getApplicationContext(), PROXY_TYPE_GO);
                } else {
                    GoProxyManager.killGoProxy();
                    log("[降级] Go代理启动失败，回退到Java代理");
                    startJavaProxy(context.getApplicationContext());
                }
            } catch (Exception e) {
                log("[切换] → Go代理异常: " + e.getMessage());
            } finally {
                isSwitching.set(false);
            }
        });
    }

    public static void switchToJavaProxy(final Context context) {
        if (!tryAcquireSwitch()) return;

        executor.execute(() -> {
            try {
                log("[切换] → Java代理");
                stopHealthCheck();
                GoProxyManager.killGoProxy();
                waitForPortReleased();
                ensureRelayServer();
                boolean success = startJavaProxy(context.getApplicationContext());
                if (success) {
                    saveProxyType(context.getApplicationContext(), PROXY_TYPE_JAVA);
                }
            } catch (Exception e) {
                log("[切换] → Java代理异常: " + e.getMessage());
            } finally {
                isSwitching.set(false);
            }
        });
    }

    public static void restartProxy(final Context context) {
        if (!tryAcquireSwitch()) return;

        executor.execute(() -> {
            try {
                int currentType = activeProxyType.get();
                log("[重启] 当前类型: " + (currentType == PROXY_TYPE_GO ? "Go" : "Java"));
                stopHealthCheck();

                if (currentType == PROXY_TYPE_GO) {
                    stopRelayServer();
                    boolean goStarted = startGoProxySync(context.getApplicationContext());
                    if (goStarted) {
                        stopJavaProxy();
                        activeProxyType.set(PROXY_TYPE_GO);
                        isProxyRunning.set(true);
                        log("[重启] Go代理重启成功，后端端口: " + GoProxyManager.getCurrentBackendPort());
                        startHealthCheck(context.getApplicationContext());
                    } else {
                        GoProxyManager.killGoProxy();
                        log("[降级] Go代理重启失败，切换到Java代理");
                        waitForPortReleased();
                        ensureRelayServer();
                        startJavaProxy(context.getApplicationContext());
                    }
                } else if (currentType == PROXY_TYPE_JAVA) {
                    boolean success = startJavaProxy(context.getApplicationContext());
                    if (!success) {
                        log("[重启] Java代理重启也失败");
                    }
                } else {
                    log("[重启] 无活跃代理，尝试启动Java代理");
                    startJavaProxy(context.getApplicationContext());
                }
            } catch (Exception e) {
                log("[重启] 异常: " + e.getMessage());
            } finally {
                isSwitching.set(false);
            }
        });
    }

    private static boolean tryAcquireSwitch() {
        if (isSwitching.get()) {
            log("[切换] 上一次切换尚未完成，请稍候");
            return false;
        }
        if (isSwitching.compareAndSet(false, true)) {
            return true;
        }
        log("[切换] 上一次切换尚未完成，请稍候");
        return false;
    }

    private static void waitForPortReleased() {
        waitForPortReleased(5000);
    }

    private static void waitForPortReleased(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            java.net.Socket socket = null;
            try {
                socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", PROXY_PORT), 200);
                socket.close();
                Thread.sleep(300);
            } catch (Exception e) {
                return;
            }
        }
        log("[端口] 等待端口释放超时: " + PROXY_PORT);
    }

    public static synchronized boolean startJavaProxy(Context context) {
        if (isJavaProxyEndpointHealthy()) {
            activeProxyType.set(PROXY_TYPE_JAVA);
            isProxyRunning.set(true);
            log("[启动] Java代理已健康运行，直接复用现有服务");
            startHealthCheck(context);
            return true;
        }
        stopJavaProxy();
        try {
            javaProxyServer = new JavaProxyServer(JAVA_BACKEND_PORT);
            boolean success = javaProxyServer.startServer();
            if (success) {
                activeProxyType.set(PROXY_TYPE_JAVA);
                isProxyRunning.set(true);
                log("[启动] Java代理成功，后端端口: " + JAVA_BACKEND_PORT);
                startHealthCheck(context);
            } else {
                activeProxyType.set(PROXY_TYPE_NONE);
                isProxyRunning.set(false);
                log("[启动] Java代理失败");
            }
            return success;
        } catch (Exception e) {
            log("[启动] Java代理异常: " + e.getMessage());
            if (isAddressInUseError(e) && isJavaProxyEndpointHealthy()) {
                activeProxyType.set(PROXY_TYPE_JAVA);
                isProxyRunning.set(true);
                log("[启动] Java代理端口已被现有实例占用，复用已有服务");
                startHealthCheck(context);
                return true;
            }
            activeProxyType.set(PROXY_TYPE_NONE);
            isProxyRunning.set(false);
            return false;
        }
    }

    public static synchronized void stopJavaProxy() {
        if (javaProxyServer != null) {
            javaProxyServer.stopServer();
            javaProxyServer = null;
        }
        if (activeProxyType.get() == PROXY_TYPE_JAVA) {
            activeProxyType.set(PROXY_TYPE_NONE);
            isProxyRunning.set(false);
        }
    }

    private static synchronized void stopRelayServer() {
        if (relayServer != null) {
            relayServer.stopServer();
            relayServer = null;
        }
    }

    public static int getActiveProxyType() {
        return activeProxyType.get();
    }

    public static int getPreferredProxyType() {
        return preferredProxyType;
    }

    public static boolean isProxyRunning() {
        return isProxyRunning.get();
    }

    public static String getProxyTypeName() {
        int type = activeProxyType.get();
        if (type == PROXY_TYPE_NONE && isJavaProxyEndpointHealthy()) type = PROXY_TYPE_JAVA;
        if (type == PROXY_TYPE_NONE) type = preferredProxyType;
        if (type == PROXY_TYPE_GO) return "Go代理";
        if (type == PROXY_TYPE_JAVA) return "Java代理";
        return "未启动";
    }

    public static String getProxyTypeShortName() {
        int type = activeProxyType.get();
        if (type == PROXY_TYPE_NONE && isJavaProxyEndpointHealthy()) type = PROXY_TYPE_JAVA;
        if (type == PROXY_TYPE_NONE) type = preferredProxyType;
        if (type == PROXY_TYPE_GO) return "Go";
        if (type == PROXY_TYPE_JAVA) return "Java";
        return "无";
    }

    public static String getProxyStatusText() {
        if (isSwitching.get()) return "切换中...";
        if (activeProxyType.get() == PROXY_TYPE_JAVA && isJavaProxyBackendHealthy()) return "运行中";
        if (activeProxyType.get() == PROXY_TYPE_GO && isGoProxyBackendHealthy()) return "运行中";
        if (!isProxyRunning.get()) return "已停止";
        return "运行中";
    }

    public static boolean canSwitchToGoProxy() {
        return GoProxyManager.isGoProxyAssetExists();
    }

    public static boolean canSwitchToJavaProxy() {
        return true;
    }

    private static void startHealthCheck(Context context) {
        synchronized (healthCheckLock) {
            if (healthCheckTimer != null) {
                healthCheckTimer.cancel();
            }
            lastSuccessTime = System.currentTimeMillis();
            healthCheckTimer = new Timer("ProxyHealthCheckTimer", true);
            healthCheckTimer.schedule(new java.util.TimerTask() {
                @Override
                public void run() {
                    try {
                        if (isSwitching.get()) return;
                        if (!isProxyHealthy()) {
                            long currentTime = System.currentTimeMillis();
                            long timeSinceLastSuccess = currentTime - lastSuccessTime;
                            log("[健康] 代理检查失败，距上次成功: " + timeSinceLastSuccess + "ms");
                            if (timeSinceLastSuccess >= RESTART_DELAY_THRESHOLD) {
                                if (isProxyRunning.get()) {
                                    isProxyRunning.set(false);
                                }
                                restartProxyWithFallback(context.getApplicationContext());
                            }
                        } else {
                            lastSuccessTime = System.currentTimeMillis();
                            if (!isProxyRunning.get()) {
                                isProxyRunning.set(true);
                                log("[健康] 检查成功，同步状态为运行中");
                            }
                        }
                    } catch (Exception e) {
                        log("[健康] 检查异常: " + e.getMessage());
                    }
                }
            }, 2000, 5000);
        }
    }

    private static void stopHealthCheck() {
        synchronized (healthCheckLock) {
            if (healthCheckTimer != null) {
                healthCheckTimer.cancel();
                healthCheckTimer = null;
            }
        }
    }

    private static void restartProxyWithFallback(Context context) {
        if (!tryAcquireSwitch()) return;

        int currentType = activeProxyType.get();
        log("[重启] 当前类型: " + (currentType == PROXY_TYPE_GO ? "Go" : "Java"));
        stopHealthCheck();

        if (currentType == PROXY_TYPE_GO) {
            GoProxyManager.killGoProxy();
            stopRelayServer();
            waitForPortReleased();
            boolean goStarted = startGoProxySync(context);
            if (goStarted) {
                activeProxyType.set(PROXY_TYPE_GO);
                isProxyRunning.set(true);
                log("[重启] Go代理重启成功，后端端口: " + GoProxyManager.getCurrentBackendPort());
                startHealthCheck(context);
                isSwitching.set(false);
                return;
            }
            GoProxyManager.killGoProxy();
            waitForPortReleased();
            log("[降级] Go代理重启失败，切换到Java代理");
            ensureRelayServer();
            startJavaProxy(context);
        } else if (currentType == PROXY_TYPE_JAVA) {
            log("[重启] Java代理不健康，尝试重启...");
            ensureRelayServer();
            boolean success = startJavaProxy(context);
            if (!success) {
                log("[重启] Java代理重启也失败");
            }
        }
        isSwitching.set(false);
    }

    public static synchronized boolean isProxyHealthy() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            final AtomicBoolean result = new AtomicBoolean(false);
            final CountDownLatch latch = new CountDownLatch(1);
            executor.execute(() -> {
                try {
                    result.set(performHealthCheck());
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(2000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result.get();
        } else {
            return performHealthCheck();
        }
    }

    private static boolean performHealthCheck() {
        int currentType = activeProxyType.get();

        if (currentType == PROXY_TYPE_JAVA && javaProxyServer != null) {
            boolean alive = javaProxyServer.isRunning();
            if (!alive) {
                log("[健康] Java代理: 服务未运行");
                return false;
            }
            try {
                String url = JAVA_HEALTH_CHECK_URL;
                String response = com.github.catvod.net.OkHttp.string(url, 1000);
                return parseHealthResponse(response);
            } catch (Exception e) {
                log("[健康] Java代理异常: " + e.getMessage());
                return alive;
            }
        }

        if (currentType == PROXY_TYPE_GO) return isGoProxyBackendHealthy();
        return isRelayHealthy();
    }

    private static boolean isJavaProxyEndpointHealthy() {
        return isJavaProxyBackendHealthy();
    }

    private static boolean isJavaProxyBackendHealthy() {
        try {
            String response = com.github.catvod.net.OkHttp.string(JAVA_HEALTH_CHECK_URL, 1000);
            return parseHealthResponse(response);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isGoProxyBackendHealthy() {
        try {
            String response = com.github.catvod.net.OkHttp.string(GoProxyManager.getCurrentHealthCheckUrl(), 1000);
            return parseHealthResponse(response);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isRelayHealthy() {
        try {
            String response = com.github.catvod.net.OkHttp.string(HEALTH_CHECK_URL, 1000);
            return parseHealthResponse(response);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAddressInUseError(Exception e) {
        if (e == null) return false;
        String message = e.getMessage();
        return message != null && (message.contains("EADDRINUSE") || message.contains("Address already in use"));
    }

    private static boolean parseHealthResponse(String response) {
        if (TextUtils.isEmpty(response)) return false;
        if ("ok".equalsIgnoreCase(response.trim())) return true;
        try {
            JsonObject json = new Gson().fromJson(response, JsonObject.class);
            if (json != null && json.has("status")) {
                return "healthy".equals(json.get("status").getAsString());
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static synchronized void ensureRelayServer() {
        if (relayServer != null && relayServer.isRunning()) return;
        relayServer = new ProxyRelayServer(PROXY_PORT, ProxyManager::resolveBackendPort);
        relayServer.startServer();
    }

    private static int resolveBackendPort() {
        int type = activeProxyType.get();
        if (type == PROXY_TYPE_GO && isGoProxyBackendHealthy()) return GoProxyManager.getCurrentBackendPort();
        if (type == PROXY_TYPE_JAVA && isJavaProxyBackendHealthy()) return JAVA_BACKEND_PORT;
        if (isJavaProxyBackendHealthy()) return JAVA_BACKEND_PORT;
        if (isGoProxyBackendHealthy()) return GoProxyManager.getCurrentBackendPort();
        return -1;
    }

    private static void saveProxyType(Context context, int type) {
        if (context == null) return;
        DanmakuConfig config = DanmakuConfigManager.getConfig(context);
        config.setProxyType(type);
        DanmakuConfigManager.saveConfig(context, config);
        preferredProxyType = type;
    }

    public static void log(String msg) {
        SpiderDebug.log(msg);
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        String newLogEntry = time + " " + Thread.currentThread().getName() + " " + msg;
        proxyLogBuffer.add(newLogEntry);
        if (proxyLogBuffer.size() > MAX_LOG_SIZE) {
            proxyLogBuffer.remove(0);
        }
    }

    public static String getLogContent(boolean reverse) {
        StringBuilder sb = new StringBuilder();
        if (reverse) {
            for (int i = proxyLogBuffer.size() - 1; i >= 0; i--) {
                sb.append(proxyLogBuffer.get(i)).append("\n");
            }
        } else {
            for (String s : proxyLogBuffer) {
                sb.append(s).append("\n");
            }
        }
        return sb.toString();
    }

    public static String getLogContent() {
        return getLogContent(false);
    }

    public static boolean hasLogs() {
        return !proxyLogBuffer.isEmpty();
    }

    public static void clearLogs() {
        proxyLogBuffer.clear();
    }
}
