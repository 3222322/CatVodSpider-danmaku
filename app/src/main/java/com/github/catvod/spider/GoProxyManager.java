package com.github.catvod.spider;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class GoProxyManager {

    public static final int DEFAULT_BACKEND_PORT = 5576;
    private static final int MAX_PORT_SCAN_COUNT = 20;
    private static final ExecutorService executor = Executors.newFixedThreadPool(5);
    static final AtomicBoolean isProxyRunning = new AtomicBoolean(false);
    private static final AtomicInteger startSessionSeq = new AtomicInteger(0);
    private static String goProxyExecutableName = "";
    private static volatile int currentBackendPort = DEFAULT_BACKEND_PORT;

    private static final class HealthProbeResult {
        final boolean healthy;
        final String body;
        final String error;

        HealthProbeResult(boolean healthy, String body, String error) {
            this.healthy = healthy;
            this.body = body == null ? "" : body;
            this.error = error == null ? "" : error;
        }
    }

    public static void initialize(Context context) {
        startGoProxyOnce(context);
    }

    static void startGoProxyOnce(Context context) {
        final int sessionId = beginStartSession();
        execute(() -> startGoProxyOnceSync(context, sessionId));
    }

    static void startGoProxyOnceSync(Context context) {
        int sessionId = beginStartSession();
        startGoProxyOnceSync(context, sessionId);
    }

    static int beginStartSession() {
        return startSessionSeq.incrementAndGet();
    }

    static void cancelStartSession() {
        startSessionSeq.incrementAndGet();
    }

    static void cancelStartSession(int sessionId) {
        startSessionSeq.compareAndSet(sessionId, sessionId + 1);
    }

    private static boolean isStartSessionActive(int sessionId) {
        return startSessionSeq.get() == sessionId;
    }

    static void startGoProxyOnceSync(Context context, int sessionId) {
        synchronized (isProxyRunning) {
            if (!isStartSessionActive(sessionId)) {
                log("[SO] 启动任务已取消，忽略本次Go代理启动");
                isProxyRunning.set(false);
                return;
            }
            boolean hasSoAssets = hasSoAssets();
            boolean hasBinaryAssets = hasBinaryAssets();

            if (hasSoAssets) {
                boolean soAvailable = GoProxyLibrary.loadLibrary();
                if (!soAvailable) {
                    log("[SO] SO资产存在，但加载失败");
                    isProxyRunning.set(false);
                    return;
                }

                log("[SO] 使用JNI方式启动Go代理");
                try {
                    String lastProbeError = "";
                    int runningState = 0;
                    try {
                        runningState = GoProxyLibrary.isProxyRunning();
                    } catch (UnsatisfiedLinkError e) {
                        log("[SO] 读取JNI运行状态失败: " + e.getMessage());
                    }

                    if (runningState == 1) {
                        if (isProxyHealthy(currentBackendPort)) {
                            isProxyRunning.set(true);
                            log("[SO] 检测到Go代理JNI已在运行，复用现有实例，端口: " + currentBackendPort);
                            return;
                        }
                        log("[SO] JNI报告Go代理已运行，但当前端口健康检查失败，尝试重新拉起");
                    }

                    try {
                        GoProxyLibrary.stopProxy();
                    } catch (UnsatisfiedLinkError e) {
                        log("[SO] 启动前清理JNI状态失败: " + e.getMessage());
                    } catch (Exception e) {
                        log("[SO] 启动前清理JNI状态异常: " + e.getMessage());
                    }

                    for (int port : buildCandidatePorts()) {
                        if (!isStartSessionActive(sessionId)) {
                            log("[SO] Go代理启动扫描已取消");
                            isProxyRunning.set(false);
                            return;
                        }
                        HealthProbeResult probe = silentHealthCheck(port, 1000);
                        if (probe.healthy && isGoHealthResponse(probe.body, port)) {
                            currentBackendPort = port;
                            isProxyRunning.set(true);
                            log("[SO] 发现健康的Go代理实例，直接复用端口: " + port);
                            return;
                        }
                        if (probe.healthy) {
                            lastProbeError = "端口 " + port + " 被非Go健康服务占用";
                            log("[探测] 端口 " + port + " 存在健康服务，但不是Go代理");
                        }
                        if (!TextUtils.isEmpty(probe.error)) {
                            lastProbeError = probe.error;
                            log("[探测] 端口 " + port + " 健康检查失败: " + probe.error);
                        }

                        int result = GoProxyLibrary.startProxy(port);
                        if (!isStartSessionActive(sessionId)) {
                            log("[SO] Go代理启动已取消，停止继续扫描端口");
                            isProxyRunning.set(false);
                            return;
                        }
                        if (result == 0) {
                            currentBackendPort = port;
                            isProxyRunning.set(true);
                            log("[SO] Go代理JNI启动成功，端口: " + port);
                            return;
                        }
                        if (result == 1) {
                            currentBackendPort = port;
                            if (isProxyHealthy(port)) {
                                isProxyRunning.set(true);
                                log("[SO] Go代理JNI报告已运行，复用端口: " + port);
                                return;
                            }
                            log("[SO] Go代理JNI报告已运行，但端口健康检查失败: " + port);
                            try {
                                GoProxyLibrary.stopProxy();
                            } catch (Exception e) {
                                log("[SO] 复用失败后停止JNI实例异常: " + e.getMessage());
                            }
                            continue;
                        }
                        if (result == 2) {
                            String nativeError = getNativeLastError();
                            if (!TextUtils.isEmpty(nativeError)) {
                                log("[SO] Go代理端口不可用(native返回2): " + nativeError + "，尝试下一个端口: " + port);
                            } else {
                                log("[SO] Go代理端口不可用(native返回2)，尝试下一个端口: " + port);
                            }
                            continue;
                        }
                        log("[SO] Go代理JNI启动失败, 端口: " + port + ", 返回码: " + result);
                    }
                    if (!TextUtils.isEmpty(lastProbeError)) {
                        log("[SO] Go代理JNI启动失败，候选端口均不可用；最后一次探测错误: " + lastProbeError);
                    } else {
                        log("[SO] Go代理JNI启动失败，候选端口均不可用");
                    }
                } catch (UnsatisfiedLinkError e) {
                    log("[SO] JNI调用失败: " + e.getMessage());
                }
                isProxyRunning.set(false);
                return;
            }

            if (hasBinaryAssets) {
                startGoProxyBinarySync(context);
            } else {
                log("[启动] Go代理所有方式均不可用");
                isProxyRunning.set(false);
            }
        }
    }

    private static List<Integer> buildCandidatePorts() {
        java.util.LinkedHashSet<Integer> ports = new java.util.LinkedHashSet<>();
        ports.add(currentBackendPort);
        ports.add(DEFAULT_BACKEND_PORT);
        ports.add(ProxyManager.PROXY_PORT); // 旧端口 5575，二进制可能监听此端口
        for (int i = 0; i < MAX_PORT_SCAN_COUNT; i++) {
            int port = DEFAULT_BACKEND_PORT + i;
            if (port == ProxyManager.JAVA_BACKEND_PORT) continue;
            ports.add(port);
        }
        return new java.util.ArrayList<>(ports);
    }

    public static int getCurrentBackendPort() {
        return currentBackendPort;
    }

    public static String getCurrentHealthCheckUrl() {
        return buildHealthCheckUrl(currentBackendPort);
    }

    private static String buildHealthCheckUrl(int port) {
        return "http://127.0.0.1:" + port + "/health";
    }

    private static void startGoProxyBinarySync(Context context) {
        try {
            if (TextUtils.isEmpty(goProxyExecutableName)) {
                List<String> abs = Arrays.asList(Build.SUPPORTED_ABIS);
                goProxyExecutableName = abs.contains("arm64-v8a") ? "goProxy-arm64" : "goProxy-arm";
            }

            java.io.File file = new java.io.File(context.getCacheDir(), goProxyExecutableName);

            java.lang.Process exec = Runtime.getRuntime().exec("/system/bin/sh");
            try (java.io.DataOutputStream dos = new java.io.DataOutputStream(exec.getOutputStream())) {
                if (!file.exists()) {
                    if (!file.createNewFile()) throw new Exception("创建文件失败 " + file);

                    java.io.InputStream is = java.util.Objects.requireNonNull(Init.get().getClass().getClassLoader()).getResourceAsStream("assets/" + goProxyExecutableName);
                    if (is == null) {
                        throw new Exception("资源文件不存在: assets/" + goProxyExecutableName);
                    }

                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
                    }
                    if (!file.setExecutable(true)) throw new Exception(goProxyExecutableName + " setExecutable is false");
                    dos.writeBytes("chmod 777 " + file.getAbsolutePath() + "\n");
                    dos.flush();
                }

                log("[二进制] 启动 " + file);
                dos.writeBytes("kill $(ps -ef | grep '" + goProxyExecutableName + "' | grep -v grep | awk '{print $2}') 2>/dev/null\n");
                dos.flush();
                dos.writeBytes("sleep 1\n");
                dos.flush();
                java.io.File logFile = new java.io.File(context.getCacheDir(), "goproxy_output.log");
                dos.writeBytes("nohup " + file.getAbsolutePath() + " > " + logFile.getAbsolutePath() + " 2>&1 &\n");
                dos.flush();
                dos.writeBytes("exit\n");
                dos.flush();
            }

            Thread.sleep(3000);

            boolean foundHealthy = false;
            for (int port : buildCandidatePorts()) {
                boolean httpOk = isProxyHealthy(port);
                log("[探测] 端口 " + port + " HTTP健康: " + httpOk);
                if (httpOk) {
                    currentBackendPort = port;
                    foundHealthy = true;
                    break;
                }
                boolean tcpOk = isTcpPortOpen(port, 800);
                log("[探测] 端口 " + port + " TCP连接: " + tcpOk);
                if (tcpOk) {
                    currentBackendPort = port;
                    foundHealthy = true;
                    break;
                }
            }
            if (foundHealthy) {
                log("[二进制] Go代理启动成功，端口: " + currentBackendPort);
                isProxyRunning.set(true);
            } else {
                log("[二进制] Go代理健康检查失败，候选端口: " + buildCandidatePorts());
                try {
                    java.io.File logFile = new java.io.File(context.getCacheDir(), "goproxy_output.log");
                    if (logFile.exists()) {
                        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(logFile))) {
                            String line;
                            int lineCount = 0;
                            while ((line = br.readLine()) != null && lineCount < 5) {
                                log("[二进制输出] " + line);
                                lineCount++;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                isProxyRunning.set(false);
            }

        } catch (Exception e) {
            log("[二进制] 启动异常: " + e.getMessage());
            isProxyRunning.set(false);
        }
    }

    public static synchronized boolean isProxyHealthy() {
        return isProxyHealthy(currentBackendPort);
    }

    public static synchronized boolean isProxyHealthy(int port) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            final java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            executor.execute(() -> {
                try {
                    result.set(performHealthCheck(port));
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return result.get();
        } else {
            return performHealthCheck(port);
        }
    }

    private static boolean isTcpPortOpen(int port, int timeoutMs) {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), timeoutMs);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean performHealthCheck(int port) {
        if (port <= 0) return false;

        if (hasSoAssets() && GoProxyLibrary.isLoaded()) {
            try {
                if (GoProxyLibrary.isProxyRunning() == 1) {
                    // JNI仅表示进程状态，真正可复用仍以HTTP健康检查为准。
                }
            } catch (UnsatisfiedLinkError ignored) {
            }
        }

        try {
            long startTime = System.currentTimeMillis();
            HealthProbeResult probe = silentHealthCheck(port, 1000);
            long elapsed = System.currentTimeMillis() - startTime;

            if (!probe.healthy || TextUtils.isEmpty(probe.body) || elapsed >= 1000) return false;
            if ("ok".equalsIgnoreCase(probe.body.trim())) return true;
            return isGoHealthResponse(probe.body, port);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isGoHealthResponse(String response, int port) {
        try {
            JsonObject json = new Gson().fromJson(response, JsonObject.class);
            if (json == null || !json.has("status")) return false;
            if (!"healthy".equalsIgnoreCase(json.get("status").getAsString())) return false;
            if (!json.has("type")) return false;
            if (!"go".equalsIgnoreCase(json.get("type").getAsString())) return false;
            if (json.has("port") && json.get("port").getAsInt() != port) return false;
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static HealthProbeResult silentHealthCheck(int port, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(buildHealthCheckUrl(port));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.connect();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return new HealthProbeResult(false, "", "HTTP " + code);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return new HealthProbeResult(true, sb.toString(), "");
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (TextUtils.isEmpty(msg)) msg = e.getClass().getSimpleName();
            return new HealthProbeResult(false, "", msg);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String getNativeLastError() {
        if (!hasSoAssets() || !GoProxyLibrary.isLoaded()) return "";
        try {
            return GoProxyLibrary.getLastError();
        } catch (Throwable e) {
            return "";
        }
    }

    public static void killGoProxy() {
        if (hasSoAssets() && GoProxyLibrary.isLoaded()) {
            try {
                GoProxyLibrary.stopProxy();
                log("[SO] Go代理JNI停止，端口: " + currentBackendPort);
                try {
                    int runningState = GoProxyLibrary.isProxyRunning();
                    if (runningState == 1) {
                        log("[SO] 停止后JNI仍报告运行中");
                    }
                } catch (UnsatisfiedLinkError e) {
                    log("[SO] 停止后检查JNI状态失败: " + e.getMessage());
                }
            } catch (UnsatisfiedLinkError e) {
                log("[SO] JNI停止失败: " + e.getMessage());
            }
        }

        try {
            if (hasBinaryAssets() && !TextUtils.isEmpty(goProxyExecutableName)) {
                java.lang.Process exec = Runtime.getRuntime().exec("/system/bin/sh");
                try (java.io.DataOutputStream dos = new java.io.DataOutputStream(exec.getOutputStream())) {
                    dos.writeBytes("kill $(ps -ef | grep '" + goProxyExecutableName + "' | grep -v grep | awk '{print $2}') 2>/dev/null\n");
                    dos.flush();
                    dos.writeBytes("exit\n");
                    dos.flush();
                }
                exec.waitFor();
            }
        } catch (Exception e) {
            log("[停止] 二进制停止异常: " + e.getMessage());
        }

        cancelStartSession();
        isProxyRunning.set(false);
        log("[停止] Go代理已终止");
    }

    public static boolean isGoProxyAssetExists() {
        return hasSoAssets() || hasBinaryAssets();
    }

    public static boolean hasSoAssets() {
        return checkAssetExists("arm64-v8a/libgoproxy.so") ||
                checkAssetExists("armeabi-v7a/libgoproxy.so");
    }

    public static boolean hasBinaryAssets() {
        try {
            if (TextUtils.isEmpty(goProxyExecutableName)) {
                List<String> abs = Arrays.asList(Build.SUPPORTED_ABIS);
                goProxyExecutableName = abs.contains("arm64-v8a") ? "goProxy-arm64" : "goProxy-arm";
            }
            java.io.InputStream is = java.util.Objects.requireNonNull(Init.get().getClass().getClassLoader()).getResourceAsStream("assets/" + goProxyExecutableName);
            if (is != null) {
                is.close();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean checkAssetExists(String path) {
        try {
            java.io.InputStream is = Init.get().getClass().getClassLoader().getResourceAsStream("assets/" + path);
            if (is != null) {
                is.close();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void execute(Runnable runnable) {
        executor.execute(runnable);
    }

    public static void log(String msg) {
        ProxyManager.log(msg);
    }
}
