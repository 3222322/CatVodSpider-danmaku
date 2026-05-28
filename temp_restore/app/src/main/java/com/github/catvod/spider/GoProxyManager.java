package com.github.catvod.spider;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.catvod.spider.Init.get;

public class GoProxyManager {

    private static final ExecutorService executor = Executors.newFixedThreadPool(5);
    static final AtomicBoolean isProxyRunning = new AtomicBoolean(false);
    private static final int PROXY_PORT = 5575;
    private static final String HEALTH_CHECK_URL = "http://127.0.0.1:" + PROXY_PORT + "/health";
    private static String goProxyExecutableName = "";

    private static Timer healthCheckTimer;
    private static final Object healthCheckLock = new Object();
    private static long lastSuccessTime = 0;
    private static final long RESTART_DELAY_THRESHOLD = 10000; // 10秒阈值

    // Go代理日志缓冲区
    private static final List<String> goProxyLogBuffer = new CopyOnWriteArrayList<>();
    private static final int MAX_LOG_SIZE = 1000;

    /**
     * 唯一的公共初始化入口
     * @param context 应用上下文
     */
    public static void initialize(Context context) {
        startGoProxyOnce(context);
    }

    /**
     * 启动独立的后台健康检查定时器
     * @param context 应用上下文
     */
    private static void startHealthCheck(Context context) {
        synchronized (healthCheckLock) {
            if (healthCheckTimer != null) {
                healthCheckTimer.cancel();
                log("旧的 GoProxy 健康检查定时器已停止。");
            }

            log("🚀 启动 GoProxy 后台健康检查...");
            lastSuccessTime = System.currentTimeMillis(); // 记录初始成功时间

            healthCheckTimer = new Timer("GoProxyHealthCheckTimer", true);
            healthCheckTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    try {
                        if (!isProxyHealthy()) {
                            long currentTime = System.currentTimeMillis();
                            long timeSinceLastSuccess = currentTime - lastSuccessTime;
                            
                            log("GoProxy 健康检查失败，距离上次成功时间: " + timeSinceLastSuccess + "ms");

                            if (timeSinceLastSuccess >= RESTART_DELAY_THRESHOLD) {
                                log("GoProxy 健康检查失败且距离上次成功超过 " + (RESTART_DELAY_THRESHOLD/1000) + " 秒，准备重启...");
                                if (isProxyRunning.get()) {
                                    isProxyRunning.set(false);
                                }

                                // Trigger the restart process.
                                startGoProxyOnce(context.getApplicationContext());
                            }
                        } else {
                            lastSuccessTime = System.currentTimeMillis(); // 更新最后成功时间
                            if (!isProxyRunning.get()) {
                                log("GoProxy 健康检查成功，同步状态为运行中");
                                isProxyRunning.set(true);
                            }
                        }
                    } catch (Exception e) {
                        log("❌ GoProxy 健康检查任务异常: " + e.getMessage());
                    }
                }
            }, 2000, 5000); // 2秒后开始，每5秒检查一次
        }
    }

    static void startGoProxyOnce(Context context) {
        execute(() -> {
            synchronized (isProxyRunning) {
                // 如果检查不健康，但状态仍是 running，则强制设置为 false
                if (isProxyRunning.get()) {
                    log("GoProxy 状态与健康检查不符，强制更新状态为未运行");
                    isProxyRunning.set(false);
                }

                log("GoProxy 未运行，开始启动流程...");
                try {
                    if (TextUtils.isEmpty(goProxyExecutableName)) {
                        List<String> abs = Arrays.asList(Build.SUPPORTED_ABIS);
                        goProxyExecutableName = abs.contains("arm64-v8a") ? "goProxy-arm64" : "goProxy-arm";
                    }

                    File file = new File(context.getCacheDir(), goProxyExecutableName);

                    Process exec = Runtime.getRuntime().exec("/system/bin/sh");
                    try (DataOutputStream dos = new DataOutputStream(exec.getOutputStream())) {
                        if (!file.exists()) {
                            if (!file.createNewFile()) throw new Exception("创建文件失败 " + file);

                            // 获取资源输入流
                            InputStream is = Objects.requireNonNull(get().getClass().getClassLoader()).getResourceAsStream("assets/" + goProxyExecutableName);
                            if (is == null) {
                                throw new Exception("资源文件不存在: assets/" + goProxyExecutableName);
                            }

                            try (FileOutputStream fos = new FileOutputStream(file)) {
                                byte[] buffer = new byte[8192];
                                int read;
                                while ((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
                            }
                            if (!file.setExecutable(true)) throw new Exception(goProxyExecutableName + " setExecutable is false");
                            dos.writeBytes("chmod 777 " + file.getAbsolutePath() + "\n");
                            dos.flush();
                        }

                        log("启动 " + file);
                        dos.writeBytes("kill $(ps -ef | grep '" + goProxyExecutableName + "' | grep -v grep | awk '{print $2}')\n");
                        dos.flush();
                        // 将输出重定向到日志文件以便收集
                        File logFile = new File(context.getCacheDir(), "goproxy_output.log");
                        dos.writeBytes("nohup " + file.getAbsolutePath() + " > " + logFile.getAbsolutePath() + " 2>&1 &\n");
                        dos.flush();
                        dos.writeBytes("exit\n");
                        dos.flush();
                    }

                    // 启动日志收集线程
                    startLogCollector(context, file);

                    Thread.sleep(3000); // 等待代理有足够的时间来启动

                    if (isProxyHealthy()) {
                        log("GoProxy 启动成功！");
                        isProxyRunning.set(true);
                        // **关键逻辑**: 只有在首次确认启动成功后，才启动健康检查来监控它
                        startHealthCheck(context);
                    } else {
                        log("GoProxy 启动后健康检查失败，请检查日志。");
                        isProxyRunning.set(false);
                    }

                } catch (Exception e) {
                    // 如果在这里捕获到异常（如文件找不到、权限问题），健康检查将不会被启动
                    log("启动 GoProxy 过程中发生严重异常，已停止后续操作: " + e.getMessage());
                    isProxyRunning.set(false);
                }
            }
        });
    }

    public static synchronized boolean isProxyHealthy() {
        return isProxyHealthyInternal();
    }

    /**
     * 内部健康检查方法，支持在主线程中安全调用
     * 如果在主线程中调用，会自动切换到后台线程执行
     */
    private static boolean isProxyHealthyInternal() {
        // 检查当前是否在主线程
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            // 在主线程中，使用同步方式在后台线程执行并等待结果
            final java.util.concurrent.atomic.AtomicBoolean result = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            executor.execute(() -> {
                try {
                    result.set(performHealthCheck());
                } finally {
                    latch.countDown();
                }
            });

            try {
                // 等待结果，最多等待2秒（1秒超时 + 1秒缓冲）
                latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log("GoProxy 健康检查等待中断");
            }
            return result.get();
        } else {
            // 不在主线程，直接执行
            return performHealthCheck();
        }
    }

    /**
     * 执行实际的健康检查（包含网络请求）
     * 此方法必须在非主线程中调用
     */
    private static boolean performHealthCheck() {
        try {
            // 记录请求开始时间
            long startTime = System.currentTimeMillis();
            String response = OkHttp.string(HEALTH_CHECK_URL, 1000);
            long elapsedTime = System.currentTimeMillis() - startTime;

            // 检查空响应（非超时情况）
            if (TextUtils.isEmpty(response)) {
                log("GoProxy 健康检查失败，返回空响应，耗时: " + elapsedTime + "ms");
                return false;
            }

            // 检查是否超时（返回空字符串且耗时接近或超过超时阈值）
            if (elapsedTime >= 1000) {
                log("GoProxy 健康检查超时，耗时: " + elapsedTime + "ms (超时阈值: 1000ms)");
                return false;
            }

            // 支持原版，检查是否为简单的健康状态字符串
            if ("ok".equalsIgnoreCase(response.trim())) {
                log("GoProxy 响应为简单字符串，已确认为健康状态，耗时: " + elapsedTime + "ms");
                return true;
            }

            // 首先尝试解析为JSON对象
            try {
                JsonObject json = new Gson().fromJson(response, JsonObject.class);
                if (json != null && json.has("status")) {
                    boolean isHealthy = "healthy".equals(json.get("status").getAsString());
                    log("GoProxy 响应为JSON对象，健康状态: " + isHealthy + "，耗时: " + elapsedTime + "ms");
                    return isHealthy;
                }
            } catch (Exception jsonEx) {
                // JSON解析失败，继续尝试其他格式
                log("GoProxy 健康检查JSON解析失败： " + jsonEx.getMessage());
            }

            log("GoProxy 健康检查失败，原始响应: " + response + "，耗时: " + elapsedTime + "ms");

            return false;
        } catch (Exception e) {
            log("GoProxy 健康检查异常: " + e);
            return false;
        }
    }

    public static void execute(Runnable runnable) {
        executor.execute(runnable);
    }

    /**
     * 启动日志收集器，捕获Go代理的输出
     * 注意：此方法通过重定向Go代理输出到文件，然后读取文件内容来实现
     * @param context 应用上下文
     * @param executableFile 可执行文件
     */
    private static void startLogCollector(Context context, File executableFile) {
        execute(() -> {
            try {
                File logFile = new File(context.getCacheDir(), "goproxy_output.log");

                // 等待日志文件被创建（最多等待10秒）
                int waitCount = 0;
                while (!logFile.exists() && waitCount < 20) {
                    Thread.sleep(500);
                    waitCount++;
                }

                if (!logFile.exists()) {
                    log("日志文件未创建，跳过日志收集");
                    return;
                }

                log("开始收集Go代理日志...");

                // 使用tail -f 实时监控日志文件
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", "tail -n 0 -f " + logFile.getAbsolutePath());
                pb.redirectErrorStream(true);
                Process tailProcess = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(tailProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty() && !line.contains("No such file or directory")) {
                        log("[Go代理] " + line);
                    }
                }
            } catch (Exception e) {
                log("日志收集器异常: " + e.getMessage());
            }
        });
    }

    /**
     * 检查Go代理可执行文件是否存在于assets中
     * @return true 如果资源文件存在
     */
    public static boolean isGoProxyAssetExists() {
        try {
            if (TextUtils.isEmpty(goProxyExecutableName)) {
                List<String> abs = Arrays.asList(Build.SUPPORTED_ABIS);
                goProxyExecutableName = abs.contains("arm64-v8a") ? "goProxy-arm64" : "goProxy-arm";
            }
            InputStream is = Objects.requireNonNull(get().getClass().getClassLoader()).getResourceAsStream("assets/" + goProxyExecutableName);
            if (is != null) {
                is.close();
                return true;
            }
        } catch (Exception e) {
            log("检查Go代理资源文件失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 记录Go代理日志（全量输出，不判断重复）
     * @param msg 日志消息
     */
    public static void log(String msg) {
        SpiderDebug.log(msg);

        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        String newLogEntry = time + " " + Thread.currentThread().getName() + " " + msg;

        // Go代理日志全量输出，不判断重复
        goProxyLogBuffer.add(newLogEntry);
        if (goProxyLogBuffer.size() > MAX_LOG_SIZE) {
            goProxyLogBuffer.remove(0);
        }
    }

    /**
     * 获取Go代理日志内容（支持倒序）
     * @param reverse 是否倒序
     * @return 日志内容字符串
     */
    public static String getLogContent(boolean reverse) {
        StringBuilder sb = new StringBuilder();
        if (reverse) {
            // 倒序输出
            for (int i = goProxyLogBuffer.size() - 1; i >= 0; i--) {
                sb.append(goProxyLogBuffer.get(i)).append("\n");
            }
        } else {
            // 正序输出
            for (String s : goProxyLogBuffer) {
                sb.append(s).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 获取Go代理日志内容（正序）
     * @return 日志内容字符串
     */
    public static String getLogContent() {
        return getLogContent(false);
    }

    /**
     * 清空Go代理日志
     */
    public static void clearLogs() {
        goProxyLogBuffer.clear();
    }
}