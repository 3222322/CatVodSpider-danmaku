package com.github.catvod.spider;

import android.text.TextUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DanmakuUtils {

    // 提取集数
    public static float extractEpisodeNum(String text) {
        if (TextUtils.isEmpty(text)) return -1;

        // 尝试匹配 "第X集"
        Pattern pattern = Pattern.compile("第\\s*(\\d+)\\s*集");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return Float.parseFloat(matcher.group(1));
            } catch (Exception e) {}
        }

        // 尝试匹配 "EP01" 或 "E01"
        pattern = Pattern.compile("[Ee][Pp]?\\s*(\\d+)");
        matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return Float.parseFloat(matcher.group(1));
            } catch (Exception e) {}
        }

        return -1;
    }

    // 提取标题（简化版）
    public static String extractTitle2(String src) {
        if (TextUtils.isEmpty(src)) return "";

        String result = src.trim();

        // 移除集数信息（更彻底）
        result = result.replaceAll("第\\s*[0-9零一二三四五六七八九十百千]+\\s*[集話话]", "");
        result = result.replaceAll("[Ee][Pp]?\\s*\\d+", "");
        result = result.replaceAll("S\\d+", "");
        result = result.replaceAll("\\d+[Kk]", "");
        // 移除文件大小信息
        result = result.replaceAll("\\[\\d+[\\.\\d]*[MGT]\\]", "");
        // 移除分辨率信息
        result = result.replaceAll("\\d+[Pp]", "");
        result = result.replaceAll("4K", "");
        // 移除文件扩展名
        result = result.replaceAll("\\.(mp4|mkv|avi|rmvb|flv|web|dl|h265|h264|hevc)$", "");
        // 移除括号内容
        result = result.replaceAll("【.*?】", "");
        result = result.replaceAll("\\[.*?\\]", "");
        result = result.replaceAll("\\(.*?\\)", "");
        // 移除特殊字符
        result = result.replaceAll("[\\\\/:*\"<>|丨]", "");
        // 清理中文标点
        result = result.replaceAll("[:：]", " ");

        // 提取中文部分（如果有）
        String chinesePart = "";
        Matcher chineseMatcher = Pattern.compile("[\\u4e00-\\u9fff]+").matcher(result);
        if (chineseMatcher.find()) {
            // 获取所有中文字符序列
            StringBuilder sb = new StringBuilder();
            while (chineseMatcher.find()) {
                sb.append(chineseMatcher.group());
            }
            chinesePart = sb.toString();
        }

        // 如果找到中文部分，优先使用中文
        if (!TextUtils.isEmpty(chinesePart)) {
            result = chinesePart.trim();
        } else {
            // 否则清理多余空格
            result = result.replaceAll("\\s+", " ").trim();
        }

        if (!src.equals(result)) {
            DanmakuSpider.log("🧹 清理标题: " + src + " -> " + result);
        }

        return result;
    }
}