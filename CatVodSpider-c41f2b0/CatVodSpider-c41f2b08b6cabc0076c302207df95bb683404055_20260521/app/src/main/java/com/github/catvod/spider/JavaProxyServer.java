package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.net.OkHttp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class JavaProxyServer {

    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)");
    private static final Pattern RANGE_PATTERN = Pattern.compile("bytes=(\\d+)-(\\d*)");

    private final ExecutorService acceptExecutor;
    private final ExecutorService downloadExecutor;
    private final int port;
    private volatile boolean running;
    private ServerSocket serverSocket;

    public JavaProxyServer(int port) {
        this.running = false;
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
            acceptExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            });
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
                final Socket socket = serverSocket.accept();
                downloadExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handleClient(socket);
                        } catch (Exception e) {
                            ProxyManager.log("处理客户端请求异常: " + e.getMessage());
                        } finally {
                            try {
                                socket.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                });
            } catch (Exception e) {
                if (running) {
                    ProxyManager.log("[连接] 接受异常: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) throws Exception {
        clientSocket.setTcpNoDelay(true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) return;

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;

        String fullPath = parts[1];
        Map<String, String> headers = new HashMap<String, String>();

        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim().toLowerCase();
                String value = line.substring(colonIdx + 1).trim();
                headers.put(key, value);
            }
        }

        String path = fullPath.split("\\?")[0];
        Map<String, String> queryParams = parseQuery(fullPath);
        OutputStream out = clientSocket.getOutputStream();

        if ("/".equals(path)) {
            writeSimpleResponse(out, 200, "text/plain", "ok");
        } else if ("/health".equals(path)) {
            String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new Date());
            String body = "{\"status\":\"healthy\",\"timestamp\":\"" + timestamp + "\"}";
            writeSimpleResponse(out, 200, "application/json", body);
        } else if ("/proxy".equals(path)) {
            handleProxy(out, headers, queryParams);
        } else {
            writeSimpleResponse(out, 404, "text/plain", "Not Found");
        }
    }

    private void handleProxy(final OutputStream out, Map<String, String> headers, Map<String, String> params) {
        String threadStr = params.get("thread");
        String chunkSizeStr = params.get("chunkSize");
        String url = params.get("url");

        String truncatedUrl;
        if (url != null && url.length() > 80) {
            truncatedUrl = url.substring(0, 80) + "...";
        } else {
            truncatedUrl = url;
        }

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

        String range = headers.get("range");
        ProxyManager.log("[请求] " + truncatedUrl + " Range=" + range + " 线程=" + threadStr + " 块=" + chunkSizeStr + "KB");

        long[] rangeArr = parseRange(range);
        final long rangeStart = rangeArr[0];
        long rangeEnd = rangeArr[1];

        final OkHttpClient client = buildOkHttpClient();
        final Map<String, String> requestHeaders = new HashMap<String, String>();

        String ua = headers.get("user-agent");
        if (!TextUtils.isEmpty(ua)) requestHeaders.put("User-Agent", ua);
        String cookie = headers.get("cookie");
        if (!TextUtils.isEmpty(cookie)) requestHeaders.put("Cookie", cookie);
        String referer = headers.get("referer");
        if (!TextUtils.isEmpty(referer)) requestHeaders.put("Referer", referer);

        final long chunkSizeBytes = chunkSizeKB * 1024L;

        try {
            long startTime = System.currentTimeMillis();

            long endPos;
            if (rangeEnd <= 0) {
                endPos = 100L;
            } else {
                endPos = rangeEnd + 1;
            }
            long firstChunkEnd = Math.min(endPos, chunkSizeBytes) + rangeStart;

            ChunkResult firstResult = downloadChunk(client, url, requestHeaders, rangeStart, firstChunkEnd, 3);

            if (firstResult == null) {
                ProxyManager.log("[首块] 下载失败, 耗时: " + (System.currentTimeMillis() - startTime) + "ms");
                writeSimpleResponse(out, 500, "text/plain", "首块下载失败");
                return;
            }

            long firstDuration = System.currentTimeMillis() - startTime;
            ProxyManager.log("[首块] 完成, 大小: " + firstResult.data.length + "B, 耗时: " + firstDuration + "ms, 状态: " + firstResult.statusCode);

            String contentRange = firstResult.responseHeaders.get("Content-Range");
            if (contentRange == null) {
                contentRange = firstResult.responseHeaders.get("content-range");
            }

            long totalSize;
            if (!TextUtils.isEmpty(contentRange)) {
                Matcher m = CONTENT_RANGE_PATTERN.matcher(contentRange);
                if (m.find()) {
                    totalSize = Long.parseLong(m.group(3));
                } else {
                    totalSize = -1L;
                }
            } else {
                totalSize = -1L;
            }

            if (totalSize <= 0) {
                ProxyManager.log("[错误] 未获取到文件总大小, Content-Range: " + contentRange);
                writeSimpleResponse(out, 500, "text/plain", "未获取到文件总大小");
                return;
            }

            if (rangeEnd <= 0) {
                rangeEnd = totalSize - 1;
            }

            final long finalRangeEnd = rangeEnd;
            long totalTransfer = finalRangeEnd - rangeStart + 1;

            double sizeMB = totalSize / 1024.0 / 1024.0;
            double transferMB = totalTransfer / 1024.0 / 1024.0;
            ProxyManager.log(String.format("[信息] 文件: %.1fMB, 传输: %.1fMB, 线程: %d, 块: %dKB, Range: %d-%d",
                    sizeMB, transferMB, threadCount, chunkSizeKB, rangeStart, finalRangeEnd));

            StringBuilder responseBuilder = new StringBuilder();
            int respCode = firstResult.statusCode == 206 ? 206 : 200;
            responseBuilder.append("HTTP/1.1 ").append(respCode)
                    .append(respCode == 206 ? " Partial Content" : " OK").append("\r\n")
                    .append("Content-Range: bytes ").append(rangeStart).append("-").append(finalRangeEnd).append("/").append(totalSize).append("\r\n")
                    .append("Accept-Ranges: bytes\r\n");

            String contentType = firstResult.responseHeaders.get("Content-Type");
            if (contentType == null) contentType = firstResult.responseHeaders.get("content-type");
            if (contentType == null) contentType = "application/octet-stream";
            responseBuilder.append("Content-Type: ").append(contentType).append("\r\n");

            for (Map.Entry<String, String> entry : firstResult.responseHeaders.entrySet()) {
                String key = entry.getKey();
                if (!"Content-Range".equalsIgnoreCase(key) &&
                        !"Content-Length".equalsIgnoreCase(key) &&
                        !"content-length".equalsIgnoreCase(key) &&
                        !"Content-Type".equalsIgnoreCase(key) &&
                        !"content-type".equalsIgnoreCase(key) &&
                        !"Transfer-Encoding".equalsIgnoreCase(key) &&
                        !"transfer-encoding".equalsIgnoreCase(key)) {
                    responseBuilder.append(key).append(": ").append(entry.getValue()).append("\r\n");
                }
            }

            responseBuilder.append("Connection: close\r\n").append("\r\n");

            out.write(responseBuilder.toString().getBytes("UTF-8"));
            out.write(firstResult.data);
            out.flush();

            ProxyManager.log("[首刷] 首块数据已发送, " + firstResult.data.length + "B");

            long currentPos = rangeStart + firstResult.data.length;
            long totalBytesSoFar = firstResult.data.length;

            int batchCount = 0;
            long[] rangeEndRef = new long[]{finalRangeEnd};

            while (currentPos <= finalRangeEnd) {
                long bytesLeft = finalRangeEnd - currentPos + 1;
                if (bytesLeft <= 0) break;

                long totalWork = bytesLeft + chunkSizeBytes - 1;
                int batchSize = (int) Math.min(threadCount, totalWork / chunkSizeBytes);
                if (batchSize <= 0) batchSize = 1;

                long batchStartTime = System.currentTimeMillis();
                batchCount++;

                final ChunkResult[] results = new ChunkResult[batchSize];
                final AtomicBoolean failed = new AtomicBoolean(false);
                final CountDownLatch latch = new CountDownLatch(batchSize);

                for (int i = 0; i < batchSize; i++) {
                    final long chunkStart = currentPos + i * chunkSizeBytes;
                    long chunkEndRaw = chunkStart + chunkSizeBytes;
                    final long chunkEnd = Math.min(chunkEndRaw, finalRangeEnd + 1);

                    final OkHttpClient fClient = client;
                    final String fUrl = url;
                    final Map<String, String> fHeaders = requestHeaders;
                    final int index = i;

                    downloadExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            ChunkResult result = null;
                            for (int retry = 0; retry < 3; retry++) {
                                try {
                                    result = downloadChunk(fClient, fUrl, fHeaders, chunkStart, chunkEnd, 3);
                                    if (result != null) break;
                                    ProxyManager.log("[重试] 块 " + chunkStart + "-" + (chunkEnd - 1) + " 第" + (retry + 1) + "次重试");
                                    Thread.sleep((retry + 1) * 1000L);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    failed.set(true);
                                    latch.countDown();
                                    return;
                                } catch (Exception e) {
                                    if (retry < 2) continue;
                                }
                            }
                            if (result == null) {
                                failed.set(true);
                                ProxyManager.log("[失败] 块 " + chunkStart + "-" + (chunkEnd - 1) + " 下载彻底失败");
                            } else {
                                results[index] = result;
                            }
                            latch.countDown();
                        }
                    });
                }

                try {
                    latch.await(120, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (failed.get()) {
                    ProxyManager.log("[中止] 批次#" + batchCount + " 下载失败");
                    return;
                }

                int written = 0;
                for (int i = 0; i < batchSize; i++) {
                    if (results[i] != null) {
                        out.write(results[i].data);
                        out.flush();
                        written += results[i].data.length;
                    }
                }

                long batchDuration = System.currentTimeMillis() - batchStartTime;
                long totalDuration = System.currentTimeMillis() - startTime;

                float speed = 0;
                if (totalDuration > 0) {
                    speed = (float) totalBytesSoFar / 1024f / 1024f / ((float) totalDuration / 1000f);
                }

                float progress = 0;
                if (totalTransfer > 0) {
                    progress = (float) totalBytesSoFar / (float) totalTransfer * 100f;
                }

                ProxyManager.log(String.format("[批次#" + batchCount + "] " + batchSize + "线程, %.1fKB, " + batchDuration + "ms | 总进度: %.1f%%, %.1fMB/s",
                        written / 1024f, progress, speed));

                currentPos += (long) batchSize * chunkSizeBytes;
                totalBytesSoFar += written;
            }

            long totalDuration = System.currentTimeMillis() - startTime;
            float avgSpeed = 0;
            if (totalDuration > 0) {
                avgSpeed = (float) totalBytesSoFar / 1024f / 1024f / ((float) totalDuration / 1000f);
            }
            ProxyManager.log(String.format("[完成] %.1fMB, " + batchCount + "批次, " + totalDuration + "ms, 平均: %.1fMB/s",
                    totalBytesSoFar / 1024f / 1024f, avgSpeed));

        } catch (Exception e) {
            ProxyManager.log("[异常] 代理处理: " + e.getMessage());
        }
    }

    private ChunkResult downloadChunk(OkHttpClient client, String url, Map<String, String> headers, long start, long end, int maxRetries) {
        ChunkResult result = null;
        Exception lastError = null;

        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                Request.Builder builder = new Request.Builder();
                builder.url(url).get();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
                builder.header("Range", "bytes=" + start + "-" + (end - 1));
                Request request = builder.build();

                Response response = client.newCall(request).execute();
                int statusCode = response.code();

                if (statusCode == 200 || statusCode == 206) {
                    byte[] data = readAllBytes(response.body().byteStream());
                    String contentType = response.header("Content-Type", "application/octet-stream");

                    Map<String, String> respHeaders = new HashMap<String, String>();
                    Headers okHeaders = response.headers();
                    for (String name : okHeaders.names()) {
                        respHeaders.put(name, response.header(name, ""));
                    }

                    response.close();
                    return new ChunkResult(data, respHeaders, statusCode, contentType);
                } else {
                    response.close();
                    throw new Exception("状态码: " + statusCode);
                }
            } catch (Exception e) {
                lastError = e;
                if (retry < maxRetries - 1) {
                    try {
                        Thread.sleep((retry + 1) * 500L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }

        ProxyManager.log("[重试" + maxRetries + "] 块 " + start + "-" + (end - 1) + " 失败: " +
                (lastError != null ? lastError.getMessage() : "unknown"));
        return null;
    }

    private OkHttpClient buildOkHttpClient() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            final X509TrustManager trustManager = new X509TrustManager() {
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
            sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());

            return new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(0, TimeUnit.SECONDS)
                    .hostnameVerifier(new HostnameVerifier() {
                        @Override
                        public boolean verify(String hostname, SSLSession session) {
                            return true;
                        }
                    })
                    .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                    .build();
        } catch (Exception e) {
            return OkHttp.client();
        }
    }

    private static byte[] readAllBytes(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1048576);
        byte[] buffer = new byte[1048576];
        int read;
        while ((read = in.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        return baos.toByteArray();
    }

    private void writeSimpleResponse(OutputStream out, int statusCode, String contentType, String body) {
        try {
            String reason;
            if (statusCode == 200) {
                reason = "OK";
            } else if (statusCode == 206) {
                reason = "Partial Content";
            } else if (statusCode == 400) {
                reason = "Bad Request";
            } else if (statusCode == 404) {
                reason = "Not Found";
            } else {
                reason = "Internal Server Error";
            }

            String response = "HTTP/1.1 " + statusCode + " " + reason +
                    "\r\nContent-Type: " + contentType +
                    "\r\nContent-Length: " + body.getBytes("UTF-8").length +
                    "\r\nConnection: close\r\n\r\n" + body;

            out.write(response.getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            ProxyManager.log("[响应] 写异常: " + e.getMessage());
        }
    }

    private Map<String, String> parseQuery(String url) {
        Map<String, String> params = new HashMap<String, String>();
        int queryIdx = url.indexOf('?');
        if (queryIdx < 0) return params;

        String query = url.substring(queryIdx + 1);
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eqIdx = pair.indexOf('=');
            if (eqIdx > 0) {
                try {
                    String key = pair.substring(0, eqIdx);
                    String value = URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8");
                    params.put(key, value);
                } catch (Exception ignored) {
                }
            }
        }
        return params;
    }

    private long[] parseRange(String range) {
        if (TextUtils.isEmpty(range)) {
            return new long[]{0L, -1L};
        }
        Matcher m = RANGE_PATTERN.matcher(range);
        if (!m.find()) {
            return new long[]{0L, -1L};
        }
        long start = Long.parseLong(m.group(1));
        long end;
        if (m.groupCount() >= 2 && !TextUtils.isEmpty(m.group(2))) {
            end = Long.parseLong(m.group(2));
        } else {
            end = -1L;
        }
        return new long[]{start, end};
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
