package com.github.catvod.spider;

public final class DanmakuUtils {

    private DanmakuUtils() {
    }

    public static float extractEpisodeNum(String text) {
        int episode = MediaIdentityResolver.parseEpisodeNumber(text);
        return episode > 0 ? episode : -1;
    }

    public static String extractTitle2(String source) {
        return MediaIdentityResolver.extractSearchTitle(source);
    }
}
