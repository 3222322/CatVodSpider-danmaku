package com.github.catvod.spider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ProxyRelayServer {

    public interface TargetResolver {
        int resolveTargetPort();
    }

    private final int listenPort;
    private final TargetResolver targetResolver;
    private final ExecutorService acceptExecutor;
    private final ExecutorService relayExecutor;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public ProxyRelayServer(int listenPort, TargetResolver targetResolver) {
        this.listenPort = listenPort;
        this.targetResolver = targetResolver;
        this.acceptExecutor = Executors.newSingleThreadExecutor();
        this.relayExecutor = new ThreadPoolExecutor(
                16, 64, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public boolean startServer() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(listenPort));
            running = true;
            ProxyManager.log("[前门] 固定代理入口启动成功，端口: " + listenPort);
            acceptExecutor.execute(this::acceptLoop);
            return true;
        } catch (Exception e) {
            ProxyManager.log("[前门] 启动失败: " + e.getMessage());
            running = false;
            return false;
        }
    }

    public void stopServer() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (Exception ignored) {
        }
        acceptExecutor.shutdown();
        relayExecutor.shutdown();
        try {
            if (!relayExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                relayExecutor.shutdownNow();
            }
            acceptExecutor.shutdownNow();
        } catch (InterruptedException e) {
            relayExecutor.shutdownNow();
            acceptExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        ProxyManager.log("[前门] 固定代理入口已停止");
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                try {
                    relayExecutor.execute(() -> handleClient(client));
                } catch (RejectedExecutionException e) {
                    ProxyManager.log("[前门] 连接过载，拒绝新请求");
                    writeServiceUnavailable(client, "Proxy overloaded");
                    closeQuietly(client);
                }
            } catch (SocketException e) {
                if (running) ProxyManager.log("[前门] 接受连接异常: " + e.getMessage());
                break;
            } catch (Exception e) {
                if (running) ProxyManager.log("[前门] 接受连接失败: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        Socket backend = null;
        try {
            int targetPort = targetResolver.resolveTargetPort();
            if (targetPort <= 0) {
                writeServiceUnavailable(client, "No backend available");
                return;
            }

            backend = new Socket();
            backend.connect(new InetSocketAddress("127.0.0.1", targetPort), 2000);
            backend.setTcpNoDelay(true);
            client.setTcpNoDelay(true);

            final Socket finalBackend = backend;
            try {
                relayExecutor.execute(() -> pipeQuietly(client, finalBackend));
            } catch (RejectedExecutionException e) {
                ProxyManager.log("[前门] 管道过载，拒绝转发");
                writeServiceUnavailable(client, "Proxy overloaded");
                return;
            }
            pipeQuietly(finalBackend, client);
        } catch (Exception e) {
            ProxyManager.log("[前门] 转发失败: " + e.getMessage());
            writeServiceUnavailable(client, "Backend unavailable");
        } finally {
            closeQuietly(backend);
            closeQuietly(client);
        }
    }

    private void pipeQuietly(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private void writeServiceUnavailable(Socket client, String message) {
        if (client == null) return;
        try {
            OutputStream out = client.getOutputStream();
            byte[] body = message.getBytes("UTF-8");
            String response = "HTTP/1.1 503 Service Unavailable\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(response.getBytes("UTF-8"));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}
