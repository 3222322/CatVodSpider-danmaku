package com.github.catvod.spider;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.github.catvod.spider.entity.DanmakuItem;
import com.github.catvod.spider.entity.Media;
import com.github.catvod.spider.danmu.SharedPreferencesService;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DanmakuScanner {

    public static String lastDetectedTitle = "";

    public static EpisodeInfo lastEpisodeInfo = null;

    private static String currentSeriesName = "";
    public static String currentEpisodeNum = "";
    private static long lastEpisodeChangeTime = 0;
    private static final long MIN_EPISODE_CHANGE_INTERVAL = 1000;

    // 视频播放状态
    private static boolean isVideoPlaying = false;
    private static long videoPlayStartTime = 0;
    private static final long MIN_PLAY_DURATION_BEFORE_PUSH = 0; // 至少播放0秒再推送
    private static final long YSC_MIN_PLAY_DURATION_BEFORE_PUSH = 3000; // 至少播放0秒再推送
    private static final long FORCE_PUSH_TIMEOUT = 10000; // 10秒后强制推送
    private static final long MAX_WAIT_FOR_PLAYBACK = 15000; // 最多等待15秒

    // View ID 生成器（兼容低版本Android）
    private static int nextViewId = 10000;

    // 延迟推送队列
    private static class PendingPush {
        DanmakuItem danmakuItem;
        Activity activity;
        String title;
        long scheduleTime;
        boolean isForcedPush = false; // 标记是否为强制推送

        PendingPush(DanmakuItem danmakuItem, Activity activity, String title, long scheduleTime) {
            this.danmakuItem = danmakuItem;
            this.activity = activity;
            this.title = title;
            this.scheduleTime = scheduleTime;
        }
    }

    private static final Map<String, PendingPush> pendingPushes = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastPushTime = new ConcurrentHashMap<>();

    private static boolean isFirstDetection = true;

    // Hook相关
    private static Timer hookTimer;
    private static volatile boolean isMonitoring = false;
    private static Timer playbackCheckTimer;
    
    // 用于确保run方法串行执行的锁
    private static final Object runLock = new Object();

    // 主线程Handler
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Runnable delayedPushTask = null;

    private static boolean isLeoButtonInjected = false;

    // 正则表达式
    private static final Pattern EPISODE_PATTERN = Pattern.compile(
            "(?:第\\s*([零一二三四五六七八九十百千万0-9]+)\\s*[集话章回])|" +
                    "(?:[Ee][Pp]?\\s*([0-9]+))|" +
                    "(?:[Ss]([0-9]+)[Ee]([0-9]+))|" +
                    "(?:\\b([0-9]{1,3})\\b)"
    );

    private static final Pattern SERIES_NAME_PATTERN = Pattern.compile(
            "([^0-9:：\\[\\]【】()（）\\-—~～]+?)\\s*[:：\\[\\]【】()（）\\-—~～]*\\s*[0-9]"
    );

    // 启动Hook监控
    public static void startHookMonitor() {
        if (hookTimer != null || isMonitoring) {
            DanmakuSpider.log("⚠️ Hook监控已在运行中");
            return;
        }

        DanmakuSpider.log("🚀 启动Hook监控");
        isMonitoring = true;
        isFirstDetection = true;

        hookTimer = new Timer("DanmakuHookTimer", true);
        hookTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (runLock) {
                    try {
                        Activity act = Utils.getTopActivity();
                        if (act != null && !act.isFinishing()) {
                            // 检查是否是播放界面
                            String className = act.getClass().getName().toLowerCase();
                            if (isPlayerActivity(className)) {
//                            DanmakuSpider.log("[Monitor] 检测到播放界面: " + className);

                                // 注入Leo弹幕按钮0
                                if (!isLeoButtonInjected) {
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                injectLeoButton(act);
                                            } catch (Exception e) {
                                                DanmakuSpider.log("❌ 按钮注入异常: " + e.getMessage());
                                            }
                                        }
                                    });
                                }

                                // 检查播放状态
//                            checkPlaybackStatus(act);

                                // Hook获取标题
//                            String newTitle = extractTitleFromView(act.getWindow().getDecorView());
                                Media media = getMedia();
                                if (media == null) return;

                                if (TextUtils.isEmpty(media.getUrl())) {
                                    return;
                                }

                                isVideoPlaying = media.isPlaying();

                                if (isVideoPlaying) {
                                    // 获取媒体信息
                                    lastEpisodeInfo = getEpisodeInfo(media, act);

                                    // 视频开始播放
                                    videoPlayStartTime = System.currentTimeMillis();
//                                    DanmakuSpider.log("▶️ 检测到视频开始播放");
                                } else {
                                    // 视频停止播放
                                    DanmakuSpider.log("⏸️ 检测到视频停止播放");

                                    // 清空缓存和队列
                                    pendingPushes.clear();
                                    lastPushTime.clear();

                                    return;
                                }

                                DanmakuConfig config = DanmakuConfigManager.getConfig(act);

                                // 检测是否开启自动查询或者已经手动查询过
                                if (!config.isAutoPushEnabled() && TextUtils.isEmpty(DanmakuManager.lastManualDanmakuUrl)) {
                                    return;
                                }

                                processDetectedTitle(act);
                            } else {
                                // 不在播放界面，重置播放状态
                                resetPlaybackStatus();

//                            DanmakuSpider.log("不在播放界面，重置播放状态");
                            }
                        }
                    } catch (Exception e) {
                        DanmakuSpider.log("❌ Hook监控异常: " + e.getMessage());
                    }
                }
            }
        }, 2000, 1000);

        // 启动播放状态检查定时器
        startPlaybackCheckTimer();
    }

    private static EpisodeInfo getEpisodeInfo(Media media, Activity act) {
        // 提取剧集信息
        String seriesName = extractSeriesName(media.getTitle());
        String episodeNum = extractEpisodeNum(media.getArtist().replace("正在播放：", ""));
        String year = extractYear(media.getArtist());
        if (TextUtils.isEmpty(year)) {
            year = extractYear2(media.getTitle());
        }
        String seasonNum = extractSeasonNum(media.getArtist());

        // 创建剧集名称列表
        List<String> episodeNames = new ArrayList<>();
        String extractTitle1 = extractTitle(media.getTitle());
        String extractTitle2 = DanmakuUtils.extractTitle2(extractTitle1);
        String extractTitle3 = null;
        if (!extractTitle2.equals(extractTitle1)) {
            extractTitle3 = DanmakuUtils.extractTitle2(media.getTitle());
        }
        String cachedName = SharedPreferencesService.getSearchKeywordCache(act, extractTitle2);

        if (!TextUtils.isEmpty(cachedName) && !cachedName.equals(media.getTitle())) {
            episodeNames.add(cachedName);
        }
        if (!TextUtils.isEmpty(extractTitle2) && !episodeNames.contains(extractTitle2)) {
            episodeNames.add(extractTitle2);
        }
        if (!TextUtils.isEmpty(extractTitle1) && !episodeNames.contains(extractTitle1)) {
            episodeNames.add(extractTitle1);
        }
        if (!TextUtils.isEmpty(extractTitle3) && !episodeNames.contains(extractTitle3)) {
            episodeNames.add(extractTitle3);
        }
        if (!TextUtils.isEmpty(media.getTitle()) && !episodeNames.contains(media.getTitle())) {
            episodeNames.add(media.getTitle());
        }

        EpisodeInfo episodeInfo = new EpisodeInfo();
        episodeInfo.setEpisodeNum(episodeNum);
        episodeInfo.setEpisodeNames(episodeNames);
        episodeInfo.setEpisodeYear(year);
        episodeInfo.setEpisodeSeasonNum(seasonNum);
        episodeInfo.setSeriesName(seriesName);
        episodeInfo.setFileName(media.getArtist().replace("正在播放：", ""));
        episodeInfo.setOriginalTitle(extractTitle2);
        episodeInfo.setEpisodeUrl(media.getUrl());

        return episodeInfo;
    }

    @Nullable
    private static Media getMedia() {
        String mediaJson = OkHttp.string("http://127.0.0.1:" + Utils.getPort() + "/media");

        if (TextUtils.isEmpty(mediaJson) || mediaJson.equals("{}")) {
            return null;
        }
//                            DanmakuSpider.log("[Monitor] mediaJson: " + mediaJson);

        Gson gson = new Gson();
        Media media = gson.fromJson(mediaJson, Media.class);

        // 兼容影视仓
        if (TextUtils.isEmpty(media.getArtist())) {
            media.setArtist(media.getTitle());
        }

        return media;
    }

    // 启动播放状态检查定时器
    private static void startPlaybackCheckTimer() {
        if (playbackCheckTimer != null) {
            playbackCheckTimer.cancel();
        }

        playbackCheckTimer = new Timer("PlaybackCheckTimer", true);
        playbackCheckTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    // 检查是否有待推送的任务
//                    DanmakuSpider.log("检查待推送的任务数量：" + pendingPushes.size());
                    if (!pendingPushes.isEmpty()) {
                        checkAndExecutePendingPushes();
                    }
                } catch (Exception e) {
                    DanmakuSpider.log("❌ 播放检查定时器异常: " + e.getMessage());
                }
            }
        }, 2000, 2000); // 2秒后开始，每2秒检查一次
    }

    // 检查播放状态
    private static void checkPlaybackStatus(Activity activity) {
        try {
            // 这里可以尝试多种方法检测播放状态
            View root = activity.getWindow().getDecorView();
            boolean wasPlaying = isVideoPlaying;

            // 方法1：检查播放器控件
            isVideoPlaying = checkIfVideoIsPlaying(root);

            // 方法2：如果没有找到播放控件，假设视频在播放（避免无法检测的情况）
//            if (!isVideoPlaying && !pendingPushes.isEmpty()) {
//                // 如果有待推送任务且未检测到播放状态，假设视频在播放
//                // 这样可以避免因播放状态检测失败而卡住
//                DanmakuSpider.log("⚠️ 未检测到播放控件，假设视频在播放");
//                isVideoPlaying = true;
//                videoPlayStartTime = System.currentTimeMillis();
//            }

            if (isVideoPlaying && !wasPlaying) {
                // 视频开始播放
                videoPlayStartTime = System.currentTimeMillis();
                DanmakuSpider.log("▶️ 检测到视频开始播放");
            } else if (!isVideoPlaying && wasPlaying) {
                // 视频停止播放
                DanmakuSpider.log("⏸️ 检测到视频停止播放");
                DanmakuManager.currentVideoSignature = "";
                DanmakuManager.lastVideoDetectedTime = 0;
                DanmakuManager.lastDanmakuId = -1;
                DanmakuSpider.resetAutoSearch();
                currentSeriesName = "";
                currentEpisodeNum = "";
                lastEpisodeChangeTime = 0;
                videoPlayStartTime = 0;

                // 清空缓存和队列
                pendingPushes.clear();
                lastPushTime.clear();
            }
        } catch (Exception e) {
            // 忽略检查错误，假设视频在播放
            if (!pendingPushes.isEmpty()) {
                DanmakuSpider.log("⚠️ 播放状态检查异常，假设视频在播放");
                isVideoPlaying = true;
                videoPlayStartTime = System.currentTimeMillis();
            }
        }
    }

    // 检查视频是否在播放
    private static boolean checkIfVideoIsPlaying(View view) {
        // 查找播放器控制UI元素
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            String text = tv.getText().toString().toLowerCase();
            // 播放器可能显示当前播放时间，如"01:23 / 45:67"
            if (text.matches("\\d{1,2}:\\d{2}\\s*/\\s*\\d{1,2}:\\d{2}") ||
                    text.matches("\\d{1,2}:\\d{2}:\\d{2}\\s*/\\s*\\d{1,2}:\\d{2}:\\d{2}")) {
                return true;
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (checkIfVideoIsPlaying(group.getChildAt(i))) {
                    return true;
                }
            }
        }

        return false;
    }

    // 重置播放状态
    private static void resetPlaybackStatus() {
        DanmakuManager.currentVideoSignature = "";
        DanmakuManager.lastVideoDetectedTime = 0;
        DanmakuManager.lastDanmakuId = -1;
        DanmakuManager.lastManualDanmakuUrl = "";
        DanmakuManager.lastAutoDanmakuUrl = "";

        currentSeriesName = "";
        currentEpisodeNum = "";
        lastEpisodeChangeTime = 0;
        isVideoPlaying = false;
        videoPlayStartTime = 0;
        isLeoButtonInjected = false;
    }

    // 检查并执行待推送任务
    private static void checkAndExecutePendingPushes() {
        long currentTime = System.currentTimeMillis();

        for (Map.Entry<String, PendingPush> entry : pendingPushes.entrySet()) {
            PendingPush push = entry.getValue();
            String key = entry.getKey();

            // 计算等待时间
            long waitTime = currentTime - push.scheduleTime;

            // 检查是否超时（超过最大等待时间）
            if (waitTime > MAX_WAIT_FOR_PLAYBACK) {
                DanmakuSpider.log("⏰ 推送任务超时（" + waitTime + "ms > " + MAX_WAIT_FOR_PLAYBACK + "ms），取消: " + key);
                pendingPushes.remove(key);
                continue;
            }

            // 检查是否达到强制推送时间（10秒）
            boolean shouldForcePush = waitTime >= FORCE_PUSH_TIMEOUT && !push.isForcedPush;

            if (shouldForcePush) {
                DanmakuSpider.log("🚨 等待" + waitTime + "ms未检测到播放状态，强制推送: " + key);
                push.isForcedPush = true;
                executePendingPush(push, true);
                pendingPushes.remove(key);
                continue;
            }

            Media media = getMedia();
            if (media == null) {
                DanmakuSpider.log("❌ 无法获取当前播放媒体信息，取消推送: " + key);
                pendingPushes.remove(key);
                continue;
            }
            // 检查视频是否在播放
            if (media.isPlaying()) {
                if (media.getState() != null) {
                    // 检查是否播放了足够长时间
                    long playDuration = currentTime - videoPlayStartTime;
                    if (playDuration >= MIN_PLAY_DURATION_BEFORE_PUSH) {
                        DanmakuSpider.log("✅ 视频已播放" + playDuration + "ms，执行推送: " + key);
                        executePendingPush(push, false);
                        pendingPushes.remove(key);
                    } else {
                        DanmakuSpider.log("⏳ 视频播放中(" + playDuration + "ms)，等待达到" + MIN_PLAY_DURATION_BEFORE_PUSH + "ms");
                    }
                } else {
                    // 检查是否播放了足够长时间
                    long playDuration = currentTime - videoPlayStartTime;
                    if (playDuration >= YSC_MIN_PLAY_DURATION_BEFORE_PUSH) {
                        DanmakuSpider.log("✅ 视频已播放" + playDuration + "ms，执行推送: " + key);
                        executePendingPush(push, false);
                        pendingPushes.remove(key);
                    } else {
                        DanmakuSpider.log("⏳ 视频播放中(" + playDuration + "ms)，等待达到" + YSC_MIN_PLAY_DURATION_BEFORE_PUSH + "ms");
                    }
                }
            } else {
                DanmakuSpider.log("⏸️ 视频未播放，已等待" + waitTime + "ms，还剩" + (FORCE_PUSH_TIMEOUT - waitTime) + "ms将强制推送");
            }
        }
    }

    // 执行待推送任务
    private static void executePendingPush(PendingPush push, boolean isForced) {
        if (push.activity == null || push.activity.isFinishing()) {
            DanmakuSpider.log("⚠️ Activity无效，取消推送");
            return;
        }

        DanmakuSpider.log("🚀 开始执行" + (isForced ? "强制" : "") + "推送: " + push.danmakuItem.getDanmakuUrl());

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LeoDanmakuService.pushDanmakuDirect(push.danmakuItem, push.activity, true);

                    // 记录推送时间，防止重复推送
                    lastPushTime.put(push.danmakuItem.getDanmakuUrl(), System.currentTimeMillis());
                } catch (Exception e) {
                    DanmakuSpider.log("❌ 推送失败: " + e.getMessage());
                }
            }
        }).start();
    }

    // 停止Hook监控
    public static void stopHookMonitor() {
        isMonitoring = false;

        // 取消延迟任务
        if (delayedPushTask != null) {
            mainHandler.removeCallbacks(delayedPushTask);
            delayedPushTask = null;
        }

        if (hookTimer != null) {
            hookTimer.cancel();
            hookTimer = null;
        }

        if (playbackCheckTimer != null) {
            playbackCheckTimer.cancel();
            playbackCheckTimer = null;
        }

        // 清空缓存和队列
        pendingPushes.clear();
        lastPushTime.clear();
        isLeoButtonInjected = false;

        DanmakuSpider.log("🛑 Hook监控已停止");
    }

    // 判断是否为播放界面
    private static boolean isPlayerActivity(String className) {
        return className.contains("videoactivity") || className.contains("detailactivity");

//        DanmakuSpider.log("className: " + className);

//        return className.contains("videoactivity") ||
//                className.contains("playeractivity") ||
//                className.contains("ijkplayer") ||
//                className.contains("exoplayer");

//        return className.contains("player") || className.contains("video") ||
//                className.contains("detail") || className.contains("play") ||
//                className.contains("media") || className.contains("movie") ||
//                className.contains("tv") || className.contains("film");
    }

    // 处理检测到的标题
    private static void processDetectedTitle(Activity activity) {
        // 生成视频签名
        String newSignature = generateSignature(lastEpisodeInfo);

//        DanmakuSpider.log("🔑 视频签名: " + newSignature);

        // 检查是否为同一个视频
        boolean isSameVideo = isSameVideo(DanmakuManager.currentVideoSignature, newSignature);;

        if (!isSameVideo) {
            // 不同的视频
            DanmakuManager.currentVideoSignature = newSignature;
            DanmakuManager.lastVideoDetectedTime = System.currentTimeMillis();

            handleEpisodeChange(activity);
        } else {
            // 同一个视频，忽略重复触发
//            DanmakuSpider.log("✅ 检测到同一个视频，忽略重复触发");
        }
    }

    // 生成签名
    private static String generateSignature(EpisodeInfo episodeInfo) {
        return episodeInfo.getEpisodeUrl();
    }

    // 判断是否为同一个视频
    private static boolean isSameVideo(String sig1, String sig2) {
        if (TextUtils.isEmpty(sig1) || TextUtils.isEmpty(sig2)) {
            return false;
        }
        return sig1.equals(sig2);
    }

    // 清理标题
    private static String extractTitle(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }

        String oldTitle = title;
        String cleanedTitle = title;

        // 规则1: 如果有《》则提取其中的内容
        if (cleanedTitle.contains("《") && cleanedTitle.contains("》")) {
            int start = cleanedTitle.indexOf("《");
            int end = cleanedTitle.indexOf("》");
            if (start < end) {
                cleanedTitle = cleanedTitle.substring(start + 1, end);
            }
        }
        // 规则2: 如果有两个及其以上的|时，截取第一个|到第二个|之间的内容
        else if (cleanedTitle.indexOf('|') != cleanedTitle.lastIndexOf('|')) {
            int firstPipe = cleanedTitle.indexOf('|');
            int secondPipe = cleanedTitle.indexOf('|', firstPipe + 1);
            if (secondPipe != -1) {
                cleanedTitle = cleanedTitle.substring(firstPipe + 1, secondPipe);
            }
        }
        // 规则3: 如果按空格截取后的是单个英文字母
        else if (cleanedTitle.contains(" ")) {
            String[] parts = cleanedTitle.split(" ");
            if (parts.length > 1 && parts[0].length() == 1 && parts[0].matches("[a-zA-Z]")) {
                int firstSpace = cleanedTitle.indexOf(" ");
                int secondSpace = cleanedTitle.indexOf(" ", firstSpace + 1);
                if (secondSpace != -1) {
                    cleanedTitle = cleanedTitle.substring(firstSpace + 1, secondSpace);
                } else {
                    cleanedTitle = cleanedTitle.substring(firstSpace + 1);
                }
            } else {
                // 原有逻辑作为兜底
                int spaceIndex = cleanedTitle.indexOf(" ");
                if (spaceIndex != -1) {
                    cleanedTitle = cleanedTitle.substring(0, spaceIndex);
                }
            }
        }

        // 原有括号逻辑作为补充
        int bracketIndex = cleanedTitle.indexOf("（");
        int englishBracketIndex = cleanedTitle.indexOf("(");
        int minIndex = Integer.MAX_VALUE;
        if (bracketIndex != -1) minIndex = Math.min(minIndex, bracketIndex);
        if (englishBracketIndex != -1) minIndex = Math.min(minIndex, englishBracketIndex);

        if (minIndex != Integer.MAX_VALUE) {
            cleanedTitle = cleanedTitle.substring(0, minIndex);
        }

        cleanedTitle = cleanedTitle.trim();

        if (!cleanedTitle.equals(oldTitle)) {
            DanmakuSpider.log("🧹 清理标题: " + oldTitle + " -> " + cleanedTitle);
        }

        return cleanedTitle;
    }


    // 提取剧集名
    private static String extractSeriesName(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }

        // 尝试匹配剧集名模式
        Matcher matcher = SERIES_NAME_PATTERN.matcher(title);
        if (matcher.find()) {
            String seriesName = matcher.group(1);
            if (seriesName != null) {
                seriesName = seriesName.trim();
                // 清理结尾的标点
                seriesName = seriesName.replaceAll("[:：\\-—~～\\[\\]【】()（）]+$", "");
                return seriesName;
            }
        }

        // 如果找不到模式，尝试提取数字前的部分
        String[] parts = title.split("[:：\\-—~～]");
        if (parts.length > 0) {
            String firstPart = parts[0].trim();
            // 移除末尾的数字
            firstPart = firstPart.replaceAll("\\s+\\d+$", "");
            if (!TextUtils.isEmpty(firstPart)) {
                return firstPart;
            }
        }

        // 最后手段：移除所有数字和标点
        return title
                .replaceAll("\\d+", "")
                .replaceAll("[:：\\-—~～\\[\\]【】()（）]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // 提取集数
    public static String extractEpisodeNum(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }

        // 日志输出（调试用）
        // DanmakuSpider.log("提取剧集数，标题: " + title);

        // 预处理：去掉常见的画质、编码、文件大小等干扰信息
        String processedTitle = preprocessTitle(title).trim();

        // 尝试各种集数格式匹配，按优先级从高到低

        // 1. 最明确的格式：S01E03, S01-E03, S01.E03 等
        Pattern seasonEpisodePattern = Pattern.compile(
                "[Ss](?:[0-9]{1,2})?[-._\\s]*[Ee]([0-9]{1,3})",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = seasonEpisodePattern.matcher(processedTitle);
        if (matcher.find()) {
            String num = matcher.group(1);
            // DanmakuSpider.log("匹配 S01E03 格式: " + num);
            return num;
        }

        // 2. 中文格式：第XX集/话/章
        Pattern chinesePattern = Pattern.compile(
                "第\\s*([零一二三四五六七八九十百千万0-9]+)\\s*[集话章节回期]"
        );
        matcher = chinesePattern.matcher(processedTitle);
        if (matcher.find()) {
            String chineseNum = matcher.group(1);
            // DanmakuSpider.log("匹配中文格式: " + chineseNum);
            return convertChineseNumberToArabic(chineseNum);
        }

        // 3. EP/E 格式：EP03, E03, Ep03
        Pattern epPattern = Pattern.compile(
                "\\b(?:EP|E)[-._\\s]*([0-9]{1,3})\\b",
                Pattern.CASE_INSENSITIVE
        );
        matcher = epPattern.matcher(processedTitle);
        if (matcher.find()) {
            String num = matcher.group(1);
            // DanmakuSpider.log("匹配 EP/E 格式: " + num);
            return num;
        }

        // 4. 带括号的完整集数：[03], (03), 【03】, （03）
        Pattern bracketFullPattern = Pattern.compile(
                "[\\[\\]()【】（）]{1,2}([0-9]{1,3})[\\[\\]()【】（）]{1,2}"
        );
        matcher = bracketFullPattern.matcher(processedTitle);
        if (matcher.find()) {
            String num = matcher.group(1);
            // 排除文件大小的情况
            if (!isLikelyFileSize(processedTitle, matcher.start(1), matcher.end(1))) {
                // DanmakuSpider.log("匹配完整括号格式: " + num);
                return num;
            } else {
                DanmakuSpider.log("排除文件大小的情况，跳过: " + num);
            }
        }

        // 5. 独立的集数数字（前面有分隔符，后面无文件大小标识）
        // 先查找所有候选数字
        List<MatchCandidate> candidates = new ArrayList<>();

        // 5.1 匹配前面有分隔符的数字
        Pattern standalonePattern = Pattern.compile(
                "(?:[\\s\\[\\]()【】（）\\-._]|^)([0-9]{1,3})(?![0-9])"
        );
        matcher = standalonePattern.matcher(processedTitle);
        while (matcher.find()) {
            String numStr = matcher.group(1);
            int start = matcher.start(1);
            int end = matcher.end(1);

            // 排除明显不是集数的情况
            if (isLikelyEpisodeNumber(processedTitle, numStr, start, end)) {
                candidates.add(new MatchCandidate(numStr, start, end,
                        calculatePriority(processedTitle, numStr, start, end)));
            } else {
                DanmakuSpider.log("排除明显不是集数的情况，跳过: " + numStr);
            }
        }

        // 5.2 匹配文件名格式：数字.分辨率/画质
        Pattern filenamePattern = Pattern.compile(
                "\\b([0-9]{1,3})\\.(?:[0-9]{3,4}[pP]|[0-9]{3,4}x[0-9]{3,4}|720|1080|480|HD|SD)\\b",
                Pattern.CASE_INSENSITIVE
        );
        matcher = filenamePattern.matcher(processedTitle);
        while (matcher.find()) {
            String numStr = matcher.group(1);
            int start = matcher.start(1);
            int end = matcher.end(1);

            if (isLikelyEpisodeNumber(processedTitle, numStr, start, end)) {
                candidates.add(new MatchCandidate(numStr, start, end,
                        calculatePriority(processedTitle, numStr, start, end) + 10)); // 额外加分
            } else {
                DanmakuSpider.log("排除文件名格式的情况，跳过: " + numStr);
            }
        }

        // 如果有多个候选，选择最优的
        if (!candidates.isEmpty()) {
            // 按优先级排序：优先级越高，位置越靠后（通常集数在文件名后面）
            Collections.sort(candidates, (a, b) -> {
                int priorityCompare = Integer.compare(b.priority, a.priority);
                if (priorityCompare != 0) return priorityCompare;
                // 优先级相同，选择位置靠后的（集数通常在文件名后面）
                return Integer.compare(b.start, a.start);
            });

            MatchCandidate best = candidates.get(0);
            // DanmakuSpider.log("选择最优匹配: " + best.number + ", 优先级: " + best.priority);
            return checkForSpecialPrefix(processedTitle, best.number, best.start);
        }

        // 6. 匹配纯数字，但要严格排除干扰
        Pattern lastResortPattern = Pattern.compile("\\b([0-9][0-9]{0,2})\\b");
        matcher = lastResortPattern.matcher(processedTitle);

        List<String> possibleEpisodes = new ArrayList<>();
        while (matcher.find()) {
            String numStr = matcher.group(1);
            int start = matcher.start(1);
            int end = matcher.end(1);

            // 严格排除文件大小、版本号等
            if (isPureEpisodeNumber(processedTitle, numStr, start, end)) {
                possibleEpisodes.add(numStr);
            }
        }

        // 7. 匹配开头或空格后的数字
        Pattern startOrSpacePattern = Pattern.compile(
                "(?:^|\\s)([0-9]{1,3})"
        );
        matcher = startOrSpacePattern.matcher(processedTitle);
        while (matcher.find()) {
            String numStr = matcher.group(1);
            int start = matcher.start(1);
            int end = matcher.end(1);

            if (isPureEpisodeNumber(processedTitle, numStr, start, end)) {
                if (!possibleEpisodes.contains(numStr)) {
                    possibleEpisodes.add(numStr);
                }
            }
        }

        // 8. 匹配结尾的数字
        Pattern endPattern = Pattern.compile(
                "([0-9]{1,3})$"
        );
        matcher = endPattern.matcher(processedTitle);
        while (matcher.find()) {
            String numStr = matcher.group(1);
            int start = matcher.start(1);
            int end = matcher.end(1);

            if (isPureEpisodeNumber(processedTitle, numStr, start, end)) {
                if (!possibleEpisodes.contains(numStr)) {
                    possibleEpisodes.add(numStr);
                }
            }
        }

        // 如果有多个可能的集数，选择看起来最合理的
        if (!possibleEpisodes.isEmpty()) {
            // 优先选择1-99之间的数字（集数通常在这个范围）
            for (String num : possibleEpisodes) {
                int value = Integer.parseInt(num);
                if (value >= 1 && value <= 99) {
                    int pos = processedTitle.indexOf(num);
                    return pos >= 0 ? checkForSpecialPrefix(processedTitle, num, pos) : num;
                }
            }
            // 如果没有1-99的，返回第一个
            String first = possibleEpisodes.get(0);
            int pos = processedTitle.indexOf(first);
            return pos >= 0 ? checkForSpecialPrefix(processedTitle, first, pos) : first;
        }

        DanmakuSpider.log("未能提取到集数，标题: " + title);

        return "";
    }

    /**
     * 预处理标题，移除常见的干扰信息
     */
    private static String preprocessTitle(String title) {
        // 移除常见的文件大小格式：如 [210.03G], (1.2GB), 等
        String processed = title
                .replaceAll("\\[[0-9]+(?:\\.[0-9]+)?[GMK]B?\\]", " ")
                .replaceAll("\\([0-9]+(?:\\.[0-9]+)?[GMK]B?\\)", " ")
                .replaceAll("【[0-9]+(?:\\.[0-9]+)?[GMK]B?】", " ")
                .replaceAll("（[0-9]+(?:\\.[0-9]+)?[GMK]B?）", " ");

        // 移除常见的画质、编码信息（但保留可能包含集数的部分）
        processed = processed
                .replaceAll("\\b(?:2160|1080|720|480)[pP]\\b", " ")
                .replaceAll("\\b(?:4K|2K|HD|SD|FHD|UHD)\\b", " ")
                .replaceAll("\\b(?:x264|x265|H264|H265|AVC|HEVC)\\b", " ")
                .replaceAll("\\b(?:AAC|AC3|DTS|FLAC)\\b", " ")
                .replaceAll("高码率|高码", " ");

        // 移除版本信息：v2, ver2.0 等
        processed = processed.replaceAll("\\b[vV](?:[0-9]+(?:\\.[0-9]+)?)\\b", " ");

        // 合并多个空格
        processed = processed.replaceAll("\\s+", " ").trim();

        DanmakuSpider.log("预处理标题: " + title + " -> " + processed);

        return processed.isEmpty() ? title : processed;
    }

    /**
     * 判断是否可能是文件大小的一部分
     */
    private static boolean isLikelyFileSize(String title, int numStart, int numEnd) {
        if (numStart < 0 || numEnd > title.length()) {
            return false;
        }

        // 检查前面是否有小数点（如 210.03G 中的 210）
        if (numStart > 0) {
            // 向前查找数字开始的位置
            int numberStart = numStart;
            while (numberStart > 0 && Character.isDigit(title.charAt(numberStart - 1))) {
                numberStart--;
            }

            // 检查数字前面是否有小数点
            if (numberStart > 1) {
                String beforeNumber = title.substring(Math.max(0, numberStart - 3), numberStart);
                if (beforeNumber.matches(".*[0-9]\\.[0-9]*$")) {
                    return true; // 是 210.03 这种格式的一部分
                }
            }
        }

        // 检查后面是否有文件大小标识
        if (numEnd < title.length()) {
            String afterNumber = title.substring(numEnd, Math.min(title.length(), numEnd + 5));
            // 匹配 .03G, G, GB, M, MB 等
            if (afterNumber.matches("^(?:\\.?[0-9]*[GMK]B?\\b).*")) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否可能是集数
     */
    private static boolean isLikelyEpisodeNumber(String title, String number, int start, int end) {
        int numValue;
        try {
            numValue = Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return false;
        }

        // 排除太小的数字（0）或太大的数字（超过1000通常不是集数）
        if (numValue == 0 || numValue > 999) {
            return false;
        }

        // 排除文件大小
        if (isLikelyFileSize(title, start, end)) {
            return false;
        }

        // 检查上下文环境
        String context = getContext(title, start, end, 10);

        // 如果上下文中有明显的集数关键词，加分
        boolean hasEpisodeKeyword = context.matches(".*(?i)(ep|episode|集|话|章|回).*");

        // 如果上下文中有明显的非集数关键词，减分
        boolean hasNonEpisodeKeyword = context.matches(".*(?i)(gb|mb|kb|size|大小|分辨率|fps|bitrate|码率).*");

        return hasEpisodeKeyword || (!hasNonEpisodeKeyword && numValue >= 1 && numValue <= 999);
    }

    /**
     * 判断是否是纯粹的集数数字（最严格的检查）
     */
    private static boolean isPureEpisodeNumber(String title, String number, int start, int end) {
        if (!isLikelyEpisodeNumber(title, number, start, end)) {
            return false;
        }

        int numValue = Integer.parseInt(number);

        // 集数通常在1-99之间，特别长的剧集可能到999
        if (numValue < 1 || numValue > 999) {
            return false;
        }

        // 检查是否在常见的集数范围内
        if (numValue >= 1 && numValue <= 99) {
            return true;
        }

        // 对于100以上的数字，需要更严格的检查
        String context = getContext(title, start, end, 20);

        // 必须有明确的集数标识
        return context.matches(".*(?i)(ep|episode|[第][0-9零一二三四五六七八九十百千万]+[集话章节回]).*");
    }

    /**
     * 计算匹配的优先级
     */
    private static int calculatePriority(String title, String number, int start, int end) {
        int priority = 0;

        // 数字本身的优先级
        int numValue = Integer.parseInt(number);
        if (numValue >= 1 && numValue <= 99) {
            priority += 10; // 常见集数范围
        }

        // 上下文优先级
        String before = start > 0 ? title.substring(Math.max(0, start - 3), start) : "";
        String after = end < title.length() ? title.substring(end, Math.min(title.length(), end + 3)) : "";

        // 前面有分隔符加分
        if (before.matches(".*[\\s\\[\\]()\\-._].*")) {
            priority += 5;
        }

        // 后面有常见视频后缀加分
        if (after.matches("^[\\.\\s\\[\\]()\\-].*")) {
            priority += 5;
        }

        // 位置靠后加分（集数通常在文件名后面）
        priority += (start * 100 / title.length());

        return priority;
    }

    /**
     * 获取数字的上下文
     */
    private static String getContext(String title, int start, int end, int windowSize) {
        int contextStart = Math.max(0, start - windowSize);
        int contextEnd = Math.min(title.length(), end + windowSize);
        return title.substring(contextStart, contextEnd);
    }

    private static String checkForSpecialPrefix(String processedTitle, String number, int startPos) {
        if (TextUtils.isEmpty(processedTitle) || TextUtils.isEmpty(number) || startPos <= 0) {
            return number;
        }
        String before = processedTitle.substring(0, startPos).trim();
        String[] keywords = {"彩蛋", "预告", "采访", "特典", "花絮", "幕后"};
        for (String kw : keywords) {
            if (before.endsWith(kw)) {
                return kw + number;
            }
        }
        return number;
    }

    /**
     * 匹配候选对象
     */
    static class MatchCandidate {
        String number;
        int start;
        int end;
        int priority;

        MatchCandidate(String number, int start, int end, int priority) {
            this.number = number;
            this.start = start;
            this.end = end;
            this.priority = priority;
        }
    }

    // 提取剧集年份
    private static String extractYear(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }

        // 业内标准：优先提取 .S 或 S 之前的年份（刮削后的文件名格式）
        // 例如：Love.Story.in.the.1970s.2026.S01E11... 应该提取 2026 而不是 1970
        Pattern seasonPattern = Pattern.compile("[.\\s]?(\\d{4})[.\\s]?[Ss]\\d+");
        Matcher seasonMatcher = seasonPattern.matcher(title);
        if (seasonMatcher.find()) {
            String year = seasonMatcher.group(1);
            // 验证年份范围合理（1980-2030）
            int yearNum = Integer.parseInt(year);
            if (yearNum >= 1900 && yearNum <= 2030) {
                return year;
            }
        }

        // 备选：匹配常见的年份格式（排除 19xxs 或 20xxs 这样的年代格式）
        Pattern yearPattern = Pattern.compile("(?<!\\d)(19|20)\\d{2}(?!\\s*[Ss])(?!\\d)");
        Matcher matcher = yearPattern.matcher(title);

        if (matcher.find()) {
            return matcher.group();
        }

        return "";
    }

    // 提取剧集年份
    private static String extractYear2(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }

        // 匹配（xxxx）格式的年份，支持中文或英文括号
        Pattern bracketPattern = Pattern.compile("[（\\(](20\\d{2}|19\\d{2})[）\\)]");
        Matcher bracketMatcher = bracketPattern.matcher(title);
        if (bracketMatcher.find()) {
            return bracketMatcher.group(1);
        }

        // 匹配空格后的年份格式
        Pattern spacePattern = Pattern.compile("\\s(20\\d{2}|19\\d{2})(?!\\d)");
        Matcher spaceMatcher = spacePattern.matcher(title);
        if (spaceMatcher.find()) {
            return spaceMatcher.group(1);
        }

        return "";
    }




    // 提取剧集季数
    private static String extractSeasonNum(String title) {
        if (TextUtils.isEmpty(title)) {
            return "";
        }

        // 匹配 S01、Season 01、S1 等季数格式
        Pattern seasonPattern = Pattern.compile("[Ss](?:eason)?\\s*(\\d{1,2})");
        Matcher matcher = seasonPattern.matcher(title);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return "";
    }


    // 中文数字转阿拉伯数字
    private static String convertChineseNumberToArabic(String chineseNum) {
        Map<Character, Integer> map = new HashMap<>();
        map.put('零', 0);
        map.put('一', 1);
        map.put('二', 2);
        map.put('三', 3);
        map.put('四', 4);
        map.put('五', 5);
        map.put('六', 6);
        map.put('七', 7);
        map.put('八', 8);
        map.put('九', 9);
        map.put('十', 10);
        map.put('百', 100);
        map.put('千', 1000);
        map.put('万', 10000);

        try {
            if (chineseNum.matches("[零一二三四五六七八九十百千万]+")) {
                int result = 0;
                int temp = 0;
                int lastUnit = 1;

                for (int i = chineseNum.length() - 1; i >= 0; i--) {
                    char c = chineseNum.charAt(i);
                    if (map.containsKey(c)) {
                        int value = map.get(c);
                        if (value >= 10) { // 单位
                            if (value > lastUnit) {
                                lastUnit = value;
                                if (temp == 0) temp = 1;
                                result += temp * value;
                                temp = 0;
                            } else {
                                lastUnit = value;
                                temp = temp == 0 ? value : temp * value;
                            }
                        } else { // 数字
                            temp += value;
                        }
                    }
                }
                result += temp;
                return String.valueOf(result);
            }
        } catch (Exception e) {
            // 转换失败，尝试直接解析数字
        }

        // 尝试直接解析为数字
        try {
            return String.valueOf(Integer.parseInt(chineseNum));
        } catch (NumberFormatException e) {
            return chineseNum;
        }
    }

    // 处理换集检测
    private static void handleEpisodeChange(Activity activity) {
        long currentTime = System.currentTimeMillis();

        // 检查是否有有效的剧集名
        if (lastEpisodeInfo.getEpisodeNames() == null || lastEpisodeInfo.getEpisodeNames().isEmpty()) {
            DanmakuSpider.log("⚠️ 未检测到剧集名，不进行换集检测");
            return;
        }
        if (TextUtils.isEmpty(lastEpisodeInfo.getEpisodeNum())) {
            DanmakuSpider.log("⚠️ 未检测到集数，不进行换集检测");
            return;
        }

        // 检查是否是同一个剧集系列
        boolean isSameSeries = isSameSeries(currentSeriesName, lastEpisodeInfo.getSeriesName());

        if (isSameSeries) {
            long timeSinceLastChange = currentTime - lastEpisodeChangeTime;

            DanmakuSpider.log("🔄 检测到同系列换集: " + currentEpisodeNum + " -> " + lastEpisodeInfo.getEpisodeNum());
            DanmakuSpider.log("⏰ 距离上次换集: " + timeSinceLastChange + "ms");
            videoPlayStartTime = System.currentTimeMillis();

            // 更新记录
            currentEpisodeNum = lastEpisodeInfo.getEpisodeNum();
            lastEpisodeChangeTime = currentTime;

            iterativeAutoSearch(lastEpisodeInfo, activity);
        } else {
            // 不同的剧集系列，更新记录
            DanmakuSpider.log("🎬 剧集名: " + lastEpisodeInfo.getEpisodeNames().get(0) + ", 年份: " + lastEpisodeInfo.getEpisodeYear() + ", 季数: " + lastEpisodeInfo.getEpisodeSeasonNum() + ", 集数: " + lastEpisodeInfo.getEpisodeNum());

            currentSeriesName = lastEpisodeInfo.getSeriesName();
            currentEpisodeNum = lastEpisodeInfo.getEpisodeNum();
            lastEpisodeChangeTime = currentTime;

            startAutoSearch(lastEpisodeInfo, activity);
        }
    }

    // 判断是否为同一个剧集系列
    private static boolean isSameSeries(String series1, String series2) {
        if (TextUtils.isEmpty(series1) || TextUtils.isEmpty(series2)) {
            return false;
        }

        // 简单的相似性检查
        String s1 = series1.replaceAll("\\s+", "");
        String s2 = series2.replaceAll("\\s+", "");

        // 如果完全相同
        if (s1.equals(s2)) {
            return true;
        }

        // 检查一个是否包含另一个
        if (s1.contains(s2) || s2.contains(s1)) {
            return true;
        }

        // 计算编辑距离（简化版）
        int minLength = Math.min(s1.length(), s2.length());
        int maxLength = Math.max(s1.length(), s2.length());

        if (minLength == 0) return false;

        // 计算相同字符比例
        int sameChars = 0;
        for (int i = 0; i < Math.min(s1.length(), s2.length()); i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                sameChars++;
            }
        }

        double similarity = (double) sameChars / maxLength;
        return similarity > 0.7; // 相似度超过70%认为是同一系列
    }

    // 安排延迟推送
    private static void scheduleDelayedPush(DanmakuItem item, Activity activity, String title, String pushKey) {
        DanmakuSpider.log("⏰ 安排延迟推送: " + pushKey);
        DanmakuSpider.log("   item: " + item.toString());
        DanmakuSpider.log("   等待视频播放后再推送（最多等待" + FORCE_PUSH_TIMEOUT/1000 + "秒）...");

        // 添加到待推送队列
        PendingPush pendingPush = new PendingPush(item, activity, title, System.currentTimeMillis());
        pendingPushes.put(pushKey, pendingPush);
    }

    // === 修改后的按钮注入逻辑 ===

    private static void injectLeoButton(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

//        DanmakuSpider.log("[按钮注入] 开始尝试注入按钮");

        try {
            // 先检查是否已经有Leo弹幕按钮存在
            View root = activity.getWindow().getDecorView();
            View existing = root.findViewWithTag("danmu_button");
            if (existing != null) {
//                DanmakuSpider.log("[按钮注入] 按钮已存在，跳过");
                return;
            }

            // 遍历视图树寻找合适的锚点按钮
            traverseForButton(root, 0);
        } catch (Exception e) {
            DanmakuSpider.log("[按钮注入] 异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 轻量级遍历寻找按钮锚点
    private static void traverseForButton(View view, int depth) {
        if (view == null || depth > 15) {
            DanmakuSpider.log("[按钮注入] 遍历结束，没有找到按钮锚点");
            return; // 增加深度限制，播放器UI可能比较深
        }

        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            CharSequence cs = tv.getText();
            if (cs != null && cs.length() > 0) {
                String text = cs.toString();
                // 寻找合适的锚点按钮
                if (text.equals("硬解") || text.equals("软解")) {
//                if (text.equals("硬解") || text.equals("软解") || text.equals("字幕") || text.equals("视轨") || text.equals("音轨")) {
                    DanmakuSpider.log("[按钮注入] 找到按钮锚点: " + text + " 深度: " + depth);
                    if (view.getParent() instanceof ViewGroup) {
                        injectButton((ViewGroup) view.getParent(), tv);
                        return; // 找到一个就返回
                    }
                }
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // 修剪：跳过列表控件
            if (view instanceof android.widget.ListView ||
                    view.getClass().getName().contains("RecyclerView")) {
                return;
            }

            for (int i = 0; i < group.getChildCount(); i++) {
                traverseForButton(group.getChildAt(i), depth + 1);
            }
        }
    }

    // 注入Leo弹幕按钮（原版逻辑）
    private static void injectButton(ViewGroup parent, TextView anchor) {
        try {
            View existing = parent.findViewWithTag("danmu_button");
            String anchorText = anchor.getText().toString();
            boolean isTargetAnchor = anchorText.contains("音轨");

            // 逻辑：如果已存在按钮
            if (existing != null) {
                // 如果当前锚点是"音轨"，我们重新定位按钮
                if (isTargetAnchor) {
                    ((ViewGroup) existing.getParent()).removeView(existing);
                    DanmakuSpider.log("[按钮注入] 移除旧按钮，准备重新注入");
                } else {
//                    DanmakuSpider.log("[按钮注入] 按钮已存在，跳过");
                    return; // 否则不重复添加
                }
            }

            if (isInRecyclerView(parent)) {
                DanmakuSpider.log("[按钮注入] 在RecyclerView中，跳过");
                return;
            }

            TextView btn;
            if (existing != null && isTargetAnchor) {
                btn = (TextView) existing; // 复用
            } else {
                // 创建新的Leo弹幕按钮
                btn = new TextView(parent.getContext());
                btn.setText("弹幕搜索");
                btn.setTag("danmu_button");
                btn.setTextColor(anchor.getTextColors());
                btn.setTextSize(0, anchor.getTextSize());
                btn.setGravity(Gravity.CENTER);
                btn.setPadding(20, 10, 20, 10);
                btn.setSingleLine(true);

                // 修复焦点问题 - 添加必要的焦点设置
                btn.setFocusable(true);
                btn.setFocusableInTouchMode(true);
                btn.setClickable(true);

                // 设置背景（使用锚点的背景）
                if (anchor.getBackground() != null && anchor.getBackground().getConstantState() != null) {
                    btn.setBackground(anchor.getBackground().getConstantState().newDrawable());
                } else {
                    btn.setBackgroundColor(Color.parseColor("#4CAF50"));
                }

                // 按钮点击事件 - 这是核心注册逻辑
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (parent.getContext() instanceof Activity) {
                            Activity activity = (Activity) parent.getContext();
                            // 添加防抖动检查
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - DanmakuSpider.lastButtonClickTime < 500) {
                                DanmakuSpider.log("[按钮点击] 防抖动：点击过于频繁");
                                return;
                            }
                            DanmakuSpider.lastButtonClickTime = currentTime;

                            DanmakuSpider.log("[按钮点击] 打开搜索对话框");
                            DanmakuUIHelper.showSearchDialog(activity, lastEpisodeInfo);
                        }
                    }
                });

                // 设置长按事件（可选）
                btn.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (parent.getContext() instanceof Activity) {
                            Activity activity = (Activity) parent.getContext();
                            // 添加防抖动检查
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - DanmakuSpider.lastButtonClickTime < 500) {
                                DanmakuSpider.log("[按钮长按] 防抖动：操作过于频繁");
                                return true;
                            }
                            DanmakuSpider.lastButtonClickTime = currentTime;

                            DanmakuSpider.log("[按钮长按] 打开远程搜索二维码");
                            DanmakuUIHelper.showQRCodeDialog((Activity) parent.getContext(), "http://" + NetworkUtils.getLocalIpAddress() + ":9810");
                        }
                        return true;
                    }
                });
            }

            // 布局参数设置
            ViewGroup.LayoutParams anchorLp = anchor.getLayoutParams();
            ViewGroup.LayoutParams params = null;

            if (anchorLp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) anchorLp);
                llp.weight = 0;
                params = llp;
            } else if (anchorLp instanceof ViewGroup.MarginLayoutParams) {
                params = new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) anchorLp);
            } else {
                params = new ViewGroup.LayoutParams(anchorLp);
            }

            // 插入位置逻辑
            int insertIndex = -1;
            boolean isInsertBefore = anchorText.equals("弹幕搜索") || anchorText.contains("搜索") || isTargetAnchor;
            int anchorIndex = parent.indexOfChild(anchor);

            if (isInsertBefore) {
                insertIndex = anchorIndex;
            } else {
                insertIndex = anchorIndex + 1;
            }

            // 如果按钮已经在正确位置，直接返回
            if (existing != null && parent.indexOfChild(existing) == insertIndex) {
                if (existing.getVisibility() != View.VISIBLE) existing.setVisibility(View.VISIBLE);
                return;
            }

            // 为RelativeLayout设置特殊规则
            if (parent instanceof android.widget.RelativeLayout) {
                android.widget.RelativeLayout.LayoutParams rlp = new android.widget.RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (anchor.getId() == View.NO_ID) {
                    // 使用兼容方式生成View ID
                    anchor.setId(generateViewId());
                }
                if (isInsertBefore) {
                    rlp.addRule(android.widget.RelativeLayout.LEFT_OF, anchor.getId());
                } else {
                    rlp.addRule(android.widget.RelativeLayout.RIGHT_OF, anchor.getId());
                }
                rlp.alignWithParent = true;
                rlp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
                params = rlp;
            }

            // 设置边距
            if (params instanceof ViewGroup.MarginLayoutParams) {
                int existingLeft = 0;
                int existingRight = 0;
                if (anchorLp instanceof ViewGroup.MarginLayoutParams) {
                    existingLeft = ((ViewGroup.MarginLayoutParams) anchorLp).leftMargin;
                    existingRight = ((ViewGroup.MarginLayoutParams) anchorLp).rightMargin;
                }

                // 如果原始边距太小，使用默认值
                if (existingLeft < 5 && existingRight < 5) {
                    existingLeft = 20;
                    existingRight = 20;
                }

                ((ViewGroup.MarginLayoutParams) params).leftMargin = existingLeft;
                ((ViewGroup.MarginLayoutParams) params).rightMargin = existingRight > 0 ? existingRight : existingLeft;
                ((ViewGroup.MarginLayoutParams) params).topMargin = 0;
                ((ViewGroup.MarginLayoutParams) params).bottomMargin = 0;
            }

            // 确保按钮在最顶层显示
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                btn.setElevation(10f); // 设置阴影层级，确保按钮在顶层
            }
            btn.bringToFront(); // 将按钮置于最前

            try {
                if (insertIndex >= 0 && insertIndex <= parent.getChildCount()) {
                    parent.addView(btn, insertIndex, params);
                } else {
                    parent.addView(btn, params);
                }

                // 重新请求布局
                parent.requestLayout();
                parent.post(new Runnable() {
                    @Override
                    public void run() {
                        // 确保按钮正确显示
                        btn.setVisibility(View.VISIBLE);
                        btn.setClickable(true);
                    }
                });

                DanmakuSpider.log("✅ Leo弹幕按钮注入成功");
                isLeoButtonInjected = true;
            } catch (Exception e) {
                DanmakuSpider.log("❌ 添加按钮失败: " + e.getMessage());
                parent.addView(btn);
            }
        } catch (Exception e) {
            DanmakuSpider.log("❌ 注入按钮异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 显示Leo按钮菜单
     * @param activity
     */
    public static void showLeoButtonMenu(final Activity activity) {
        try {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity);
            builder.setTitle("弹幕设置");

            // 构建菜单选项列表
            java.util.List<String> optionsList = new java.util.ArrayList<>();
            optionsList.add("📱 远程搜索/输入");
            optionsList.add("🔄 Auto 推送开关");
            optionsList.add("🔇 静默模式");
            optionsList.add("💬 弹幕配置");
            optionsList.add("🎨 布局配置");
            optionsList.add("✨ 弹幕 UI 风格");
            optionsList.add("📝 查看日志");

            // 只有当Go代理资源文件存在时才添加相关按钮
            final boolean isGoProxyExists = GoProxyManager.isGoProxyAssetExists();
            if (isGoProxyExists) {
                String proxyStatus = GoProxyManager.isProxyRunning.get() ? "运行中" : "已停止";
                String proxyHealth = GoProxyManager.isProxyHealthy() ? "健康" : "异常";
                String proxyStatusText = GoProxyManager.isProxyRunning.get() ? proxyStatus + " | " + proxyHealth : proxyStatus;
                optionsList.add("🔌 Go代理状态 [" + proxyStatusText + "]");
                optionsList.add("🔄 重启Go代理");
            }

            String[] options = optionsList.toArray(new String[0]);

            builder.setItems(options, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    switch (which) {
                        case 0: // 远程搜索/输入
                            DanmakuSpider.log("[菜单] 打开远程搜索二维码");
                            String qrUrl = "http://" + NetworkUtils.getLocalIpAddress() + ":9810";
                            DanmakuUIHelper.showQRCodeDialog(activity, qrUrl);
                            break;

                        case 1: // 自动推送开关
                            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
                            // 切换自动推送状态
                            config.setAutoPushEnabled(!config.isAutoPushEnabled());
                            DanmakuConfigManager.saveConfig(activity, config);
                        
                            // 更新 UI 显示
                            DanmakuSpider.log("自动推送弹幕状态切换：" + config.isAutoPushEnabled());
                            Utils.safeShowToast(activity,
                                    config.isAutoPushEnabled() ? "自动推送弹幕已开启" : "自动推送弹幕已关闭");
                        
                            // 如果关闭了自动推送，可以停止相关监控
                            if (!config.isAutoPushEnabled()) {
                                pendingPushes.clear();
                                DanmakuSpider.log("[菜单] 已清空待推送队列");
                            }
                        
                            break;
                        
                        case 2: // 静默模式
                            DanmakuConfig configSilent = DanmakuConfigManager.getConfig(activity);
                            // 切换静默模式状态
                            configSilent.setSilentMode(!configSilent.isSilentMode());
                            DanmakuConfigManager.saveConfig(activity, configSilent);
                        
                            // 更新 UI 显示
                            DanmakuSpider.log("静默模式状态切换：" + configSilent.isSilentMode());
                            Utils.safeShowToast(activity,
                                    configSilent.isSilentMode() ? "静默模式已开启" : "静默模式已关闭");
                        
                            break;

                        case 3: // 弹幕设置
                            DanmakuSpider.log("[菜单] 打开弹幕设置");
                            DanmakuUIHelper.showConfigDialog(activity);
                            break;
                        case 4: // 布局配置
                            DanmakuSpider.log("[菜单] 打开布局配置");
                            DanmakuUIHelper.showLpConfigDialog(activity);
                            break;
                        case 5: // 弹幕 UI 风格
                            DanmakuSpider.log("[菜单] 打开弹幕 UI 风格");
                            DanmakuUIHelper.showDanmakuStyleDialog(activity);
                            break;
                        case 6: // 查看日志（统一日志查看器）
                            DanmakuSpider.log("[菜单] 打开统一日志查看器");
                            DanmakuUIHelper.showUnifiedLogDialog(activity);
                            break;
                    }

                    // 处理Go代理相关按钮（如果存在）
                    if (isGoProxyExists) {
                        switch (which) {
                            case 7: // Go 代理状态
                                String status = GoProxyManager.isProxyRunning.get() ? "运行中" : "已停止";
                                String health = GoProxyManager.isProxyHealthy() ? "健康" : "异常";
                                String toastMsg = GoProxyManager.isProxyRunning.get() ?
                                        "Go代理状态: " + status + "\n健康检查: " + health :
                                        "Go代理状态: " + status;
                                Utils.safeShowToast(activity, toastMsg);
                                DanmakuSpider.log("[菜单] 查看Go代理状态: " + toastMsg);
                                break;

                            case 8: // 重启 Go 代理
                                DanmakuSpider.log("[菜单] 用户触发Go代理重启");
                                GoProxyManager.isProxyRunning.set(false);
                                GoProxyManager.startGoProxyOnce(activity.getApplicationContext());
                                Utils.safeShowToast(activity, "Go代理重启中，请稍候...");
                                break;
                        }
                    }
                }
            });

            builder.setNegativeButton("取消", null);

            try {
                builder.show();
            } catch (Exception e) {
                DanmakuSpider.log("显示菜单失败: " + e.getMessage());
            }
        } catch (Exception e) {
            DanmakuSpider.log("创建菜单失败: " + e.getMessage());
        }
    }

    // 兼容低版本的View ID生成方法
    private static int generateViewId() {
        // 确保ID为正数且不与其他ID冲突
        nextViewId++;
        // 确保ID不会超过Android允许的最大值（0xFFFFFF，因为高8位有特殊用途）
        if (nextViewId > 0x00FFFFFF) {
            nextViewId = 10000;
        }
        return nextViewId;
    }

    // 检查是否在RecyclerView中
    private static boolean isInRecyclerView(View view) {
        View p = view;
        while (p != null) {
            if (p.getClass().getName().contains("RecyclerView")) return true;
            if (p.getParent() instanceof View) {
                p = (View) p.getParent();
            } else {
                break;
            }
        }
        return false;
    }
    // === 按钮注入逻辑结束 ===

    // 提取标题的辅助方法
    private static String extractTitleFromView(View view) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            String text = tv.getText().toString().trim();
            if (isPotentialTitle(text)) {
                return text;
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE) {
                    String result = extractTitleFromView(child);
                    if (!TextUtils.isEmpty(result)) {
                        return result;
                    }
                }
            }
        }
        return "";
    }

    private static boolean isPotentialTitle(String text) {
        if (TextUtils.isEmpty(text) || text.length() < 2 || text.length() > 200) return false;

        if (text.matches(".*\\d{1,2}:\\d{1,2}(:\\d{1,2})?.*")) return false;
        if (text.matches("\\d+")) return false;

        String lower = text.toLowerCase();
        if (lower.contains("loading") || lower.contains("buffering") ||
                lower.contains("error") || lower.contains("fail")) {
            return false;
        }

        return true;
    }

    private static void startAutoSearch(EpisodeInfo episodeInfo, final Activity activity) {
        DanmakuConfig config = DanmakuConfigManager.getConfig(activity);

        // 检查自动推送状态
        if (!config.isAutoPushEnabled()) {
            DanmakuSpider.log("❌ 自动推送已关闭，跳过自动搜索");
            return;
        }

        iterativeAutoSearch(episodeInfo, activity);
    }

    private static void iterativeAutoSearch(EpisodeInfo episodeInfo, final Activity activity) {
        new Thread(() -> {
            List<String> episodeNames = episodeInfo.getEpisodeNames();
            if (episodeNames == null || episodeNames.isEmpty()) {
                DanmakuSpider.log("❌ 剧集名称列表为空，无法执行迭代搜索");
                return;
            }

            LeoDanmakuService.SearchResult bestResult = null;

            DanmakuSpider.log("迭代列表：" + episodeNames);

            for (String name : episodeNames) {
                if (TextUtils.isEmpty(name)) continue;

                DanmakuSpider.log("🔍 迭代搜索中，尝试名称: " + name);
                LeoDanmakuService.SearchResult currentResult = LeoDanmakuService.autoSearch(name, episodeInfo, activity);

                if (currentResult.found) {
                    if (currentResult.similarity == 1.0) {
                        DanmakuSpider.log("✅ 找到完全匹配结果，立即推送并停止搜索");
                        LeoDanmakuService.pushDanmakuDirect(currentResult.item, activity, true);
                        return; // 找到完全匹配结果，结束方法
                    }

                    if (bestResult == null || currentResult.similarity > bestResult.similarity) {
                        bestResult = currentResult;
                    }
                }
            }

            if (bestResult != null && bestResult.similarity >= 0.85) {
                DanmakuSpider.log("🏁 迭代搜索结束，未找到完全匹配结果。推送最佳匹配项 (相似度: " + bestResult.similarity + ")");
                LeoDanmakuService.pushDanmakuDirect(bestResult.item, activity, true);
            } else {
                // 输出分析对比信息
                String inputName = episodeInfo.getEpisodeNames().get(0);
                if (bestResult != null && bestResult.item != null) {
                    DanmakuSpider.log("📊 分析对比 - 输入剧名: " + inputName + ", 最佳匹配: " + bestResult.item.getTitle() + ", 相似度: " + bestResult.similarity + " (低于0.85阈值)");
                } else {
                    DanmakuSpider.log("📊 分析对比 - 输入剧名: " + inputName + ", 未找到任何匹配结果");
                }
                DanmakuSpider.log("🤷‍♂️ 迭代搜索结束，未找到任何有效弹幕");
                if (activity != null && !activity.isFinishing()) {
//                    activity.runOnUiThread(() -> Utils.safeShowToast(activity, "Leo弹幕获取失败，请手动搜索"));
                    DanmakuSpider.log("Leo弹幕获取失败，请手动搜索");
                }
            }
        }).start();
    }
}
