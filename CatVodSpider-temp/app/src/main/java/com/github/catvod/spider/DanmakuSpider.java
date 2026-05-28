package com.github.catvod.spider;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.spider.entity.DanmakuItem;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public class DanmakuSpider extends Spider {

    public static String apiUrl = "";
    private static boolean initialized = false;
    private static File sCacheDir = null;
    private static WebServer webServer;

    // 日志
    private static final List<String> logBuffer = new CopyOnWriteArrayList<>();
    private static final int MAX_LOG_SIZE = 1000;

    /**
     * 添加一个时间戳变量来防止 Leo弹幕 按钮快速连续点击：
     */
    public static long lastButtonClickTime = 0;

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        doInitWork(context, extend);
    }

    public static void clearCache(Context context) {
        File cacheDir = new File(context.getCacheDir(), "leo_danmaku_cache");
        if (cacheDir.exists()) {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }
        DanmakuScanner.lastDetectedTitle = "";
        DanmakuSpider.resetAutoSearch();
    }

    public static synchronized void doInitWork(Context context, String extend) {
        // 初始化缓存目录
        sCacheDir = new File(context.getCacheDir(), "leo_danmaku_cache");
        if (!sCacheDir.exists()) sCacheDir.mkdirs();

        // 初始化配置
        DanmakuConfig config = DanmakuConfigManager.loadConfig(context);
        if (!TextUtils.isEmpty(extend)) {
            if (extend.startsWith("http")) {
                config.getApiUrls().addAll(Arrays.asList(extend.split(",")));
            } else if (extend.startsWith("{") && extend.endsWith("}")) {
                try {
                    JSONObject jsonObject = new JSONObject(extend);
                    config.updateFromJson(jsonObject);
                } catch (Exception e) {
                    log("解析JSON格式配置失败: " + e.getMessage());
                }
            }
        }

        DanmakuConfigManager.saveConfig(context, config);

        if (initialized) return;

        // 启动WebServer
        try {
            webServer = new WebServer(9810);
        } catch (IOException e) {
            e.printStackTrace();
        }

        log("Leo弹幕插件 v1.0 初始化完成");
        initialized = true;
    }

    // 重置自动搜索状态
    public static void resetAutoSearch() {
        DanmakuManager.resetAutoSearch();
    }

    // 记录弹幕URL
    public static void recordDanmakuUrl(DanmakuItem danmakuItem, boolean isAuto) {
        DanmakuManager.recordDanmakuUrl(danmakuItem, isAuto);
    }

    // 获取下一个弹幕ID
    public static DanmakuItem getNextDanmakuItem(int currentEpisodeNum, int newEpisodeNum) {
        return DanmakuManager.getNextDanmakuItem(currentEpisodeNum, newEpisodeNum);
    }

    // 日志记录
    public static void log(String msg) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        String newLogEntry = time + " " + Thread.currentThread().getName() + " " + msg;

        SpiderDebug.log(newLogEntry);

        // 检查最后一条日志是否与当前消息相同，如果相同则不添加
        if (!logBuffer.isEmpty()) {
            String lastLogEntry = logBuffer.get(logBuffer.size() - 1);
            // 提取最后一条日志的消息部分进行比较（去掉时间和线程名）
            // 查找第一个和第二个空格的位置
            int firstSpaceIndex = lastLogEntry.indexOf(' ');
            if (firstSpaceIndex != -1) {
                int secondSpaceIndex = lastLogEntry.indexOf(' ', firstSpaceIndex + 1);
                if (secondSpaceIndex != -1) {
                    String lastMsg = lastLogEntry.substring(secondSpaceIndex + 1);
                    if (lastMsg.equals(msg)) {
                        return; // 如果消息相同，则直接返回，不添加到日志缓冲区
                    }
                }
            }
        }

        logBuffer.add(newLogEntry);
        if (logBuffer.size() > MAX_LOG_SIZE) {
            logBuffer.remove(0);
        }
    }

    public static String getLogContent() {
        return getLogContent(false);
    }

    /**
     * 获取日志内容（支持倒序）
     * @param reverse 是否倒序
     * @return 日志内容字符串
     */
    public static String getLogContent(boolean reverse) {
        StringBuilder sb = new StringBuilder();
        if (reverse) {
            // 倒序输出
            for (int i = logBuffer.size() - 1; i >= 0; i--) {
                sb.append(logBuffer.get(i)).append("\n");
            }
        } else {
            // 正序输出
            for (String s : logBuffer) {
                sb.append(s).append("\n");
            }
        }
        return sb.toString();
    }

    public static void clearLogs() {
        logBuffer.clear();
    }

    // TVBox接口
    @Override
    public String homeContent(boolean filter) {
        try {
            JSONObject result = new JSONObject();
            JSONArray classes = new JSONArray();
            classes.put(createClass("leo_danmaku_config", "Leo弹幕设置"));
            result.put("class", classes);
            result.put("list", new JSONArray());
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        try {
            DanmakuConfig config = DanmakuConfigManager.getConfig(Objects.requireNonNull(Utils.getTopActivity()));
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();

            // 创建弹幕配置按钮
            JSONObject configVod = createVod("config", "弹幕配置", "", "配置弹幕API");
            list.put(configVod);

            // 创建自动推送弹幕按钮（保持开启状态）
            JSONObject autoPushVod = createVod("auto_push", "自动推送弹幕", "",
                    config.isAutoPushEnabled() ? "已开启" : "已关闭");
            list.put(autoPushVod);

            // 创建静默模式按钮
            JSONObject silentModeVod = createVod("silent_mode", "静默模式", "",
                    config.isSilentMode() ? "已开启" : "已关闭");
            list.put(silentModeVod);

            // 创建查看日志按钮（统一日志查看器）
            JSONObject logVod = createVod("log", "查看日志", "", "弹幕/Go代理日志");
            list.put(logVod);

            // 创建布局配置按钮
            JSONObject lpConfigVod = createVod("lp_config", "布局配置", "", "调整弹窗大小和透明度");
            list.put(lpConfigVod);

            // 创建弹幕UI风格切换按钮
            JSONObject styleVod = createVod("danmaku_style", "弹幕UI风格", "",
                    "当前: " + config.getDanmakuStyle());
            list.put(styleVod);

            // 只有当Go代理资源文件存在时才显示相关按钮
            if (GoProxyManager.isGoProxyAssetExists()) {
                // 创建Go代理状态按钮
                String proxyStatus = GoProxyManager.isProxyRunning.get() ? "运行中" : "已停止";
                String proxyHealth = GoProxyManager.isProxyHealthy() ? "健康" : "异常";
                String proxyStatusText = GoProxyManager.isProxyRunning.get() ? proxyStatus + " | " + proxyHealth : proxyStatus;
                JSONObject proxyStatusVod = createVod("proxy_status", "Go代理状态", "",
                        proxyStatusText);
                list.put(proxyStatusVod);

                // 创建Go代理重启按钮
                JSONObject proxyRestartVod = createVod("proxy_restart", "重启Go代理", "",
                        "点击重启代理服务");
                list.put(proxyRestartVod);
            }

            result.put("list", list);
            result.put("page", 1);
            result.put("pagecount", 1);
            result.put("limit", 20);
            result.put("total", list.length());
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }


    @Override
    public String detailContent(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        final String id = ids.get(0);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                final Activity ctx = Utils.getTopActivity();
                if (ctx != null && !ctx.isFinishing()) {
                    ctx.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                DanmakuConfig config = DanmakuConfigManager.getConfig(ctx);
                                if (id.equals("config")) {
                                    DanmakuUIHelper.showConfigDialog(ctx);
                                } else if (id.equals("auto_push")) {
                                    // 切换自动推送状态
                                    config.setAutoPushEnabled(!config.isAutoPushEnabled());
                                    DanmakuConfigManager.saveConfig(ctx, config);
                                
                                    // 更新 UI 显示
                                    DanmakuSpider.log("自动推送状态切换：" + config.isAutoPushEnabled());
                                    Utils.safeShowToast(ctx,
                                            config.isAutoPushEnabled() ? "自动推送已开启" : "自动推送已关闭");
                                
                                    // 重新加载页面以更新状态显示
                                    refreshCategoryContent(ctx);
                                } else if (id.equals("silent_mode")) {
                                    // 切换静默模式状态
                                    config.setSilentMode(!config.isSilentMode());
                                    DanmakuConfigManager.saveConfig(ctx, config);
                                
                                    // 更新 UI 显示
                                    DanmakuSpider.log("静默模式状态切换：" + config.isSilentMode());
                                    Utils.safeShowToast(ctx,
                                            config.isSilentMode() ? "静默模式已开启" : "静默模式已关闭");
                                
                                    // 重新加载页面以更新状态显示
                                    refreshCategoryContent(ctx);
                                } else if (id.equals("log")) {
                                    DanmakuUIHelper.showUnifiedLogDialog(ctx);
                                } else if (id.equals("lp_config")) {
                                    DanmakuUIHelper.showLpConfigDialog(ctx);
                                } else if (id.equals("danmaku_style")) {
                                    DanmakuUIHelper.showDanmakuStyleDialog(ctx);
                                } else if (id.equals("proxy_status")) {
                                    // 显示Go代理状态详情
                                    String status = GoProxyManager.isProxyRunning.get() ? "运行中" : "已停止";
                                    String health = GoProxyManager.isProxyHealthy() ? "健康" : "异常";
                                    String toastMsg = GoProxyManager.isProxyRunning.get() ?
                                            "Go代理状态: " + status + "\n健康检查: " + health :
                                            "Go代理状态: " + status;
                                    Utils.safeShowToast(ctx, toastMsg);
                                    refreshCategoryContent(ctx);
                                } else if (id.equals("proxy_restart")) {
                                    // 重启Go代理
                                    DanmakuSpider.log("用户触发Go代理重启");
                                    GoProxyManager.isProxyRunning.set(false);
                                    GoProxyManager.startGoProxyOnce(ctx.getApplicationContext());
                                    Utils.safeShowToast(ctx, "Go代理重启中，请稍候...");
                                    // 延迟刷新以显示最新状态
                                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            refreshCategoryContent(ctx);
                                        }
                                    }, 3000);
                                }
                            } catch (Exception e) {
                                DanmakuSpider.log("显示对话框失败: " + e.getMessage());
                                Utils.safeShowToast(ctx,
                                        "请稍后再试");
                            }
                        }
                    });
                }
            }
        }, 100); // 延迟100ms，确保Activity稳定

        try {
            DanmakuConfig config = DanmakuConfigManager.getConfig(Objects.requireNonNull(Utils.getTopActivity()));
            JSONObject vod = new JSONObject();
            vod.put("vod_id", id);
            vod.put("vod_name", id.equals("auto_push") ? "自动推送弹幕" :
                    id.equals("silent_mode") ? "静默模式" :
                    id.equals("log") ? "查看日志" : id.equals("lp_config") ? "布局配置" :
                            id.equals("danmaku_style") ? "弹幕 UI 风格" :
                            id.equals("proxy_status") ? "Go 代理状态" :
                            id.equals("proxy_restart") ? "重启 Go 代理" : "Leo 弹幕设置");
            vod.put("vod_pic", "");
            String proxyStatus = GoProxyManager.isProxyRunning.get() ? "运行中" : "已停止";
            String proxyHealth = GoProxyManager.isProxyHealthy() ? "健康" : "异常";
            String proxyStatusText = GoProxyManager.isProxyRunning.get() ? proxyStatus + " | " + proxyHealth : proxyStatus;
            vod.put("vod_remarks", id.equals("auto_push") ?
                    (config.isAutoPushEnabled() ? "已开启" : "已关闭") :
                    id.equals("silent_mode") ?
                            (config.isSilentMode() ? "已开启" : "已关闭") :
                    id.equals("log") ? "弹幕/Go 代理日志" : id.equals("lp_config") ? "调整弹窗大小和透明度" :
                            id.equals("danmaku_style") ? "当前：" + config.getDanmakuStyle() :
                            id.equals("proxy_status") ? proxyStatusText :
                            id.equals("proxy_restart") ? "点击重启代理服务" : "请稍候...");
            vod.put("vod_play_url", "");
            vod.put("vod_play_from", "");
            JSONObject result = new JSONObject();
            JSONArray list = new JSONArray();
            list.put(vod);
            result.put("list", list);
            return result.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // 添加刷新分类内容的方法
    public void refreshCategoryContent(Activity ctx) {
        try {
            String content = categoryContent("", "", false, new HashMap<>());
            if (!TextUtils.isEmpty(content)) {
                JSONObject result = new JSONObject(content);
                JSONArray list = result.getJSONArray("list");
                DanmakuConfig config = DanmakuConfigManager.getConfig(ctx);

                // 找到自动推送按钮并更新其 remark
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    if ("auto_push".equals(item.getString("vod_id"))) {
                        item.put("vod_remarks", config.isAutoPushEnabled() ? "已开启" : "已关闭");
                    } else if ("silent_mode".equals(item.getString("vod_id"))) {
                        item.put("vod_remarks", config.isSilentMode() ? "已开启" : "已关闭");
                    } else if ("danmaku_style".equals(item.getString("vod_id"))) {
                        item.put("vod_remarks", "当前：" + config.getDanmakuStyle());
                    } else if ("proxy_status".equals(item.getString("vod_id"))) {
                        String status = GoProxyManager.isProxyRunning.get() ? "运行中" : "已停止";
                        String health = GoProxyManager.isProxyHealthy() ? "健康" : "异常";
                        String statusText = GoProxyManager.isProxyRunning.get() ? status + " | " + health : status;
                        item.put("vod_remarks", statusText);
                    }
                }
            }
        } catch (Exception e) {
            DanmakuSpider.log("刷新分类内容失败: " + e.getMessage());
        }
    }


    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "";
    }

    private JSONObject createClass(String id, String name) throws Exception {
        JSONObject cls = new JSONObject();
        cls.put("type_id", id);
        cls.put("type_name", name);
        return cls;
    }

    private JSONObject createVod(String id, String name, String pic, String remark) throws Exception {
        JSONObject vod = new JSONObject();
        vod.put("vod_id", id);
        vod.put("vod_name", name);
        vod.put("vod_pic", pic);
        vod.put("vod_remarks", remark);
        return vod;
    }
}
