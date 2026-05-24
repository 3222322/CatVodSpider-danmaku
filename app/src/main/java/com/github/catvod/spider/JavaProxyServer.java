package com.github.catvod.spider;

import android.text.TextUtils;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaProxyServer {

    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)");
    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d+)-(\\d*)");

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final ExecutorService acceptExecutor;
    private final ExecutorService downloadExecutor;
    private final int port;

    public JavaProxyServer(int port) {
        this.port = port;
        this.acceptExecutor = Executors.newSingleThreadExecutor();
        this.downloadExecutor = Executors.newCachedThreadPool();
    }

    public boolean startServer() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            running = true;
            ProxyManager.log("[启动] Java代理服务, 端口: " + port);
            acceptExecutor.execute(this::acceptLoop);
            return true;
        } catch (Exception e) {
            ProxyManager.log("[错误] 启动失败: " + e.getMessage());
            running = false;
            return false;
        }
    }

    public void stopServer() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        downloadExecutor.shutdownNow();
        acceptExecutor.shutdownNow();
        ProxyManager.log("[停止] Java代理服务已停止");
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                downloadExecutor.execute(() -> {
                    try {
                        handleClient(client);
                    } catch (Exception e) {
                        ProxyManager.log("处理客户端请求异常: " + e.getMessage());
                    } finally {
                        try { client.close(); } catch (Exception ignored) {}
                    }
                });
            } catch (Exception e) {
                if (running) ProxyManager.log("[连接] 接受异常: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) throws SocketException {
        client.setTcpNoDelay(true);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String uri = parts[1];
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
            }

            String path = uri.split("\\?")[0];
            Map<String, String> params = parseQuery(uri);
            OutputStream out = client.getOutputStream();

            if ("/".equals(path)) {
                writeSimpleResponse(out, 200, "text/plain", "ok");
            } else if ("/health".equals(path)) {
                String json = "{\"status\":\"healthy\",\"type\":\"java\",\"port\":" + port + ",\"timestamp\":\"" +
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new java.util.Date()) + "\"}";
                writeSimpleResponse(out, 200, "application/json", json);
            } else if ("/proxy".equals(path)) {
                handleProxy(out, headers, params);
            } else {
                writeSimpleResponse(out, 404, "text/plain", "Not Found");
            }
        } catch (Exception e) {
            if (running) ProxyManager.log("[客户端] 处理异常: " + e.getMessage());
        }
    }

    private void handleProxy(OutputStream out, Map<String, String> headers,
                              Map<String, String> params) {
        String threadStr = params.get("thread");
        String chunkSizeStr = params.get("chunkSize");
        String url = params.get("url");

        String shortUrl = url != null && url.length() > 80 ? url.substring(0, 80) + "..." : url;

        if (TextUtils.isEmpty(threadStr) || TextUtils.isEmpty(chunkSizeStr) || TextUtils.isEmpty(url)) {
            ProxyManager.log("[拒绝] 参数不完整");
            writeSimpleResponse(out, 400, "text/plain", "参数不完整");
            return;
        }

        int threadCount;
        int chunkSizeKB;
        try {
            threadCount = Integer.parseInt(threadStr);
            chunkSizeKB = Integer.parseInt(chunkSizeStr);
        } catch (NumberFormatException e) {
            writeSimpleResponse(out, 400, "text/plain", "参数格式错误");
            return;
        }

        String rangeHeader = headers.get("range");
        ProxyManager.log("[请求] " + shortUrl + " Range=" + rangeHeader + " 线程=" + threadStr + " 块=" + chunkSizeStr + "KB");
        long[] range = parseRange(rangeHeader);
        long startPos = range[0];
        long endPos = range[1];

        okhttp3.OkHttpClient client = buildOkHttpClient();

        Map<String, String> forwardHeaders = new HashMap<>();
        String ua = headers.get("user-agent");
        if (!TextUtils.isEmpty(ua)) forwardHeaders.put("User-Agent", ua);
        String cookie = headers.get("cookie");
        if (!TextUtils.isEmpty(cookie)) forwardHeaders.put("Cookie", cookie);
        String referer = headers.get("referer");
        if (!TextUtils.isEmpty(referer)) forwardHeaders.put("Referer", referer);

        long chunkSize = (long) chunkSizeKB * 1024;

        try {
            long t0 = System.currentTimeMillis();
            ChunkResult firstResult = downloadChunk(client, url, forwardHeaders, startPos,
                    startPos + Math.min(endPos <= 0 ? 100 : endPos + 1, chunkSize), 3);

            if (firstResult == null) {
                ProxyManager.log("[首块] 下载失败, 耗时: " + (System.currentTimeMillis() - t0) + "ms");
                writeSimpleResponse(out, 500, "text/plain", "首块下载失败");
                return;
            }

            long firstChunkTime = System.currentTimeMillis() - t0;
            ProxyManager.log("[首块] 完成, 大小: " + firstResult.data.length + "B, 耗时: " + firstChunkTime + "ms, 状态: " + firstResult.statusCode);

            String contentRangeHeader = firstResult.responseHeaders.get("Content-Range");
            if (contentRangeHeader == null) contentRangeHeader = firstResult.responseHeaders.get("content-range");
            long fileSize = -1;

            if (!TextUtils.isEmpty(contentRangeHeader)) {
                Matcher m = CONTENT_RANGE_PATTERN.matcher(contentRangeHeader);
                if (m.find()) {
                    fileSize = Long.parseLong(m.group(3));
                }
            }

            if (fileSize <= 0) {
                ProxyManager.log("[错误] 未获取到文件总大小, Content-Range: " + contentRangeHeader);
                writeSimpleResponse(out, 500, "text/plain", "未获取到文件总大小");
                return;
            }

            if (endPos <= 0) {
                endPos = fileSize - 1;
            }

            final long finalEndPos = endPos;
            long totalBytes = finalEndPos - startPos + 1;

            ProxyManager.log("[信息] 文件: " + String.format("%.1f", fileSize / 1024.0 / 1024.0) + "MB" +
                    ", 传输: " + String.format("%.1f", totalBytes / 1024.0 / 1024.0) + "MB" +
                    ", 线程: " + threadCount + ", 块: " + chunkSizeKB + "KB" +
                    ", Range: " + startPos + "-" + finalEndPos);

            StringBuilder headerBuilder = new StringBuilder();
            int status = firstResult.statusCode == 206 ? 206 : 200;
            headerBuilder.append("HTTP/1.1 ").append(status).append(status == 206 ? " Partial Content" : " OK").append("\r\n");
            headerBuilder.append("Content-Range: bytes ").append(startPos).append("-").append(finalEndPos).append("/").append(fileSize).append("\r\n");
            headerBuilder.append("Accept-Ranges: bytes\r\n");

            String contentType = firstResult.responseHeaders.get("Content-Type");
            if (contentType == null) contentType = firstResult.responseHeaders.get("content-type");
            if (contentType == null) contentType = "application/octet-stream";
            headerBuilder.append("Content-Type: ").append(contentType).append("\r\n");

            for (Map.Entry<String, String> entry : firstResult.responseHeaders.entrySet()) {
                String key = entry.getKey();
                if ("Content-Range".equalsIgnoreCase(key) || "Content-Length".equalsIgnoreCase(key)
                        || "Content-Type".equalsIgnoreCase(key) || "content-range".equalsIgnoreCase(key)
                        || "content-length".equalsIgnoreCase(key) || "content-type".equalsIgnoreCase(key)
                        || "Transfer-Encoding".equalsIgnoreCase(key) || "transfer-encoding".equalsIgnoreCase(key)) {
                    continue;
                }
                headerBuilder.append(key).append(": ").append(entry.getValue()).append("\r\n");
            }

            headerBuilder.append("Connection: close\r\n");
            headerBuilder.append("\r\n");

            out.write(headerBuilder.toString().getBytes("UTF-8"));
            out.write(firstResult.data);
            out.flush();
            ProxyManager.log("[首刷] 首块数据已发送, " + firstResult.data.length + "B");

            long nextPos = startPos + firstResult.data.length;
            long batchChunkSize = chunkSize * threadCount;
            long totalSent = firstResult.data.length;
            int batchIndex = 0;

            for (long batchStart = nextPos; batchStart <= finalEndPos; batchStart += batchChunkSize) {
                long remaining = finalEndPos - batchStart + 1;
                if (remaining <= 0) break;

                int batchThreads = (int) Math.min(threadCount, (remaining + chunkSize - 1) / chunkSize);
                if (batchThreads <= 0) batchThreads = 1;

                long batchT0 = System.currentTimeMillis();
                batchIndex++;

                ChunkResult[] results = new ChunkResult[batchThreads];
                AtomicBoolean hasError = new AtomicBoolean(false);
                CountDownLatch latch = new CountDownLatch(batchThreads);

                for (int i = 0; i < batchThreads; i++) {
                    long cs = batchStart + (long) i * chunkSize;
                    long ce = Math.min(cs + chunkSize, finalEndPos + 1);
                    if (cs > finalEndPos) break;

                    final int idx = i;
                    downloadExecutor.execute(() -> {
                        try {
                            ChunkResult result = null;
                            for (int retry = 0; retry < 3; retry++) {
                                result = downloadChunk(client, url, forwardHeaders, cs, ce, 3);
                                if (result != null) break;
                                ProxyManager.log("[重试] 块 " + cs + "-" + (ce - 1) + " 第" + (retry + 1) + "次重试");
                                Thread.sleep((retry + 1) * 1000L);
                            }
                            if (result == null) {
                                hasError.set(true);
                                ProxyManager.log("[失败] 块 " + cs + "-" + (ce - 1) + " 下载彻底失败");
                            } else {
                                results[idx] = result;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            hasError.set(true);
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                latch.await(120, TimeUnit.SECONDS);

                if (hasError.get()) {
                    ProxyManager.log("[中止] 批次#" + batchIndex + " 下载失败");
                    return;
                }

                int batchBytes = 0;
                for (int i = 0; i < batchThreads; i++) {
                    if (results[i] != null) {
                        out.write(results[i].data);
                        batchBytes += results[i].data.length;
                        out.flush();
                    }
                }

                totalSent += batchBytes;
                long batchTime = System.currentTimeMillis() - batchT0;
                long elapsed = System.currentTimeMillis() - t0;
                float speed = elapsed > 0 ? (totalSent / 1024.0f / 1024.0f) / (elapsed / 1000.0f) : 0;
                float progress = totalBytes > 0 ? (totalSent * 100.0f / totalBytes) : 0;
                ProxyManager.log("[批次#" + batchIndex + "] " + batchThreads + "线程, " +
                        String.format("%.1f", batchBytes / 1024.0f) + "KB, " + batchTime + "ms" +
                        " | 总进度: " + String.format("%.1f", progress) + "%" +
                        ", " + String.format("%.1f", speed) + "MB/s");
            }

            long totalTime = System.currentTimeMillis() - t0;
            float avgSpeed = totalTime > 0 ? (totalSent / 1024.0f / 1024.0f) / (totalTime / 1000.0f) : 0;
            ProxyManager.log("[完成] " + String.format("%.1f", totalSent / 1024.0f / 1024.0f) + "MB" +
                    ", " + batchIndex + "批次, " + totalTime + "ms" +
                    ", 平均: " + String.format("%.1f", avgSpeed) + "MB/s");

        } catch (Exception e) {
            ProxyManager.log("[异常] 代理处理: " + e.getMessage());
        }
    }

    private void writeSimpleResponse(OutputStream out, int status, String contentType, String body) {
        try {
            String statusText = status == 200 ? "OK" : status == 206 ? "Partial Content" :
                    status == 400 ? "Bad Request" : status == 404 ? "Not Found" : "Internal Server Error";
            String response = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Content-Length: " + body.getBytes("UTF-8").length + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" + body;
            out.write(response.getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            ProxyManager.log("[响应] 写异常: " + e.getMessage());
        }
    }

    private okhttp3.OkHttpClient buildOkHttpClient() {
        try {
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            final javax.net.ssl.X509TrustManager trustAll = new javax.net.ssl.X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                }

                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }
            };
            sslContext.init(null, new javax.net.ssl.TrustManager[]{trustAll}, new java.security.SecureRandom());

            return new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(0, TimeUnit.SECONDS)
                    .hostnameVerifier((hostname, session) -> true)
                    .sslSocketFactory(sslContext.getSocketFactory(), trustAll)
                    .build();
        } catch (Exception e) {
            return com.github.catvod.net.OkHttp.client();
        }
    }

    private ChunkResult downloadChunk(okhttp3.OkHttpClient client, String url,
                                       Map<String, String> headers, long start, long end, int maxRetries) {
        Exception lastErr = null;
        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                okhttp3.Request.Builder reqBuilder = new okhttp3.Request.Builder().url(url).get();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    reqBuilder.header(entry.getKey(), entry.getValue());
                }
                reqBuilder.header("Range", "bytes=" + start + "-" + (end - 1));

                okhttp3.Response resp = client.newCall(reqBuilder.build()).execute();
                int status = resp.code();
                if (status == 200 || status == 206) {
                    byte[] data = readAllBytes(resp.body().byteStream());
                    String contentType = resp.header("Content-Type", "application/octet-stream");
                    Map<String, String> respHeaders = new HashMap<>();
                    for (String name : resp.headers().names()) {
                        respHeaders.put(name, resp.header(name, ""));
                    }
                    resp.close();
                    return new ChunkResult(data, respHeaders, status, contentType);
                }
                resp.close();
                lastErr = new Exception("状态码: " + status);
            } catch (Exception e) {
                lastErr = e;
            }
            if (retry < maxRetries - 1) {
                try { Thread.sleep((retry + 1) * 500L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        ProxyManager.log("[重试" + maxRetries + "] 块 " + start + "-" + (end - 1) + " 失败: " + (lastErr != null ? lastErr.getMessage() : "unknown"));
        return null;
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1024 * 1024);
        byte[] buf = new byte[1024 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private long[] parseRange(String rangeStr) {
        if (TextUtils.isEmpty(rangeStr)) return new long[]{0, -1};
        Matcher m = RANGE_PATTERN.matcher(rangeStr);
        if (!m.find()) return new long[]{0, -1};
        long start = Long.parseLong(m.group(1));
        long end = -1;
        if (m.groupCount() >= 2 && !TextUtils.isEmpty(m.group(2))) {
            end = Long.parseLong(m.group(2));
        }
        return new long[]{start, end};
    }

    private Map<String, String> parseQuery(String uri) {
        Map<String, String> params = new HashMap<>();
        int q = uri.indexOf('?');
        if (q < 0) return params;
        String query = uri.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    params.put(pair.substring(0, eq),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                } catch (Exception ignored) {}
            }
        }
        return params;
    }

    private static class ChunkResult {
        final byte[] data;
        final Map<String, String> responseHeaders;
        final int statusCode;
        final String contentType;

        ChunkResult(byte[] data, Map<String, String> responseHeaders, int statusCode, String contentType) {
            this.data = data;
            this.responseHeaders = responseHeaders;
            this.statusCode = statusCode;
            this.contentType = contentType;
        }
    }
}
