package com.github.catvod.spider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for title, alias, season and episode parsing.
 */
public final class MediaIdentityResolver {

    private static final Pattern SERIES_NAME_PATTERN = Pattern.compile(
            "([^0-9:：\\[\\]【】()（）\\-—~～]+?)\\s*[:：\\[\\]【】()（）\\-—~～]*\\s*[0-9]"
    );
    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "(?i)[\\[【(（]?\\s*[0-9]+(?:\\.[0-9]+)?\\s*[GMK]B?\\s*[\\]】)）]?"
    );
    private static final Pattern QUALITY_PATTERN = Pattern.compile(
            "(?i)[\\[【(（]?(?:HDR10\\+?|HDR VIVID|DOLBY[ .-]?VISION|杜比视界|"
                    + "DOLBY[ .-]?ATMOS|杜比全景声|IMAX ENHANCED|HDR|4K|8K|2K|"
                    + "UHD|FHD|HD|SD|2160P|1080P|720P|480P|"
                    + "HEVC|H\\.?26[45]|X26[45]|AVC|AV1|60FPS|60帧|"
                    + "高码率|高码|AAC|AC3|DTS|FLAC|ATMOS|"
                    + "WEB[- .]?DL|WEBRIP|BLURAY|REMUX|SDR|"
                    + "国粤双语|国语|粤语|中字|简中|繁中|内封|外挂|"
                    + "无水印|未删减|完整版|臻彩|臻享)[\\]】)）]?"
    );
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:V|VER)\\.?\\s*\\d+(?:\\.\\d+)?(?![A-Za-z0-9])"
    );
    private static final Pattern SEASON_EPISODE_PATTERN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:S(?:eason)?\\s*(\\d{1,2}))?[ ._-]*"
                    + "E(?:P(?:ISODE)?)?\\s*(\\d{1,4})(?![0-9])"
    );
    private static final Pattern SEASON_PATTERN = Pattern.compile(
            "(?:第\\s*([0-9零一二三四五六七八九十]+)\\s*季)|(?i)(?:S|SEASON)\\s*(\\d{1,2})"
    );
    private static final Pattern CHINESE_EPISODE_PATTERN = Pattern.compile(
            "第\\s*([零一二三四五六七八九十百千万0-9]+)\\s*[集话章节回期]"
    );
    private static final Pattern EPISODE_PATTERN = Pattern.compile(
            "\\b(?:EP|E)[-._\\s]*([0-9]{1,3})\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BRACKET_EPISODE_PATTERN = Pattern.compile(
            "[\\[\\]()【】（）]{1,2}([0-9]{1,3})[\\[\\]()【】（）]{1,2}"
    );
    private static final Pattern STANDALONE_PATTERN = Pattern.compile(
            "(?:[\\s\\[\\]()【】（）\\-._]|^)([0-9]{1,3})(?![0-9])"
    );
    private static final Pattern FILENAME_EPISODE_PATTERN = Pattern.compile(
            "\\b([0-9]{1,3})\\.(?:[0-9]{3,4}[pP]|[0-9]{3,4}x[0-9]{3,4}|720|1080|480|HD|SD)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LAST_RESORT_PATTERN = Pattern.compile("\\b([0-9][0-9]{0,2})\\b");
    private static final Pattern START_OR_SPACE_PATTERN = Pattern.compile(
            "(?:^|\\s)([0-9]{1,3})(?![0-9])"
    );
    private static final Pattern ENDING_NUMBER_PATTERN = Pattern.compile(
            "(?<![0-9])([0-9]{1,3})$"
    );
    private static final Pattern SIZE_TRAILING_EPISODE_PATTERN = Pattern.compile(
            "(?i)(?:\\[\\s*[0-9.]+\\s*[GMK]B?\\s*\\]|\\b(?:4K|8K))\\s*"
                    + "([0-9]{1,4})(?:\\s*\\.[A-Za-z0-9]{2,5})?\\s*$"
    );
    private static final Pattern YEAR_BEFORE_SEASON_PATTERN = Pattern.compile(
            "[.\\s]?(\\d{4})[.\\s]?[Ss]\\d+"
    );
    private static final Pattern YEAR_PATTERN = Pattern.compile(
            "(?<!\\d)(19|20)\\d{2}(?!\\s*[Ss])(?!\\d)"
    );
    private static final Pattern YEAR_BRACKET_PATTERN = Pattern.compile(
            "[（(](20\\d{2}|19\\d{2})[）)]"
    );
    private static final Pattern YEAR_SPACE_PATTERN = Pattern.compile(
            "\\s(20\\d{2}|19\\d{2})(?!\\d)"
    );
    private static final Pattern EXTENSION_PATTERN = Pattern.compile(
            "(?i)\\.(?:mp4|mkv|avi|rmvb|flv|ts|m3u8|webm)$"
    );
    private static final Pattern FIRST_NUMBER_PATTERN = Pattern.compile("([0-9]+)");

    private MediaIdentityResolver() {
    }

    public static final class Identity {
        public final String rawTitle;
        public final String rawArtist;
        public final String fileName;
        public final String canonicalTitle;
        public final String displayTitle;
        public final String seriesTitle;
        public final String originalTitle;
        public final String year;
        public final String episodeToken;
        public final int season;
        public final int episode;
        public final List<String> searchNames;

        private Identity(String rawTitle, String rawArtist, String fileName,
                         String canonicalTitle, String displayTitle, String seriesTitle,
                         String originalTitle, String year, String episodeToken,
                         int season, int episode, List<String> searchNames) {
            this.rawTitle = rawTitle;
            this.rawArtist = rawArtist;
            this.fileName = fileName;
            this.canonicalTitle = canonicalTitle;
            this.displayTitle = displayTitle;
            this.seriesTitle = seriesTitle;
            this.originalTitle = originalTitle;
            this.year = year;
            this.episodeToken = episodeToken;
            this.season = Math.max(1, season);
            this.episode = Math.max(0, episode);
            this.searchNames = Collections.unmodifiableList(searchNames);
        }

        public String getPrimarySearchName() {
            return searchNames.isEmpty() ? canonicalTitle : searchNames.get(0);
        }

        public int[] getSeasonEpisode() {
            return new int[]{season, episode};
        }
    }

    public static Identity resolve(String rawTitle, String rawArtist, String rawUrl) {
        String title = stripPlayingPrefix(rawTitle);
        String artist = stripPlayingPrefix(rawArtist);
        String urlFileName = extractUrlFileName(rawUrl);
        String fileName = stripExtension(artist);

        String extracted = extractTitle(title);
        String displayTitle = extractSearchTitle(extracted);
        String originalTitle = extractSearchTitle(title);
        String seriesTitle = extractSeriesName(displayTitle);
        String canonicalTitle = firstNonEmpty(
                displayTitle, originalTitle, extractSearchTitle(fileName), seriesTitle
        );

        String episodeSource = firstNonEmpty(fileName, title, urlFileName);
        String seasonSource = firstNonEmpty(fileName, title, urlFileName);
        String yearSource = firstNonEmpty(fileName, title, urlFileName);

        String episodeToken = extractEpisodeToken(episodeSource);
        if (isEmpty(episodeToken)) episodeToken = extractEpisodeToken(title);
        if (isEmpty(episodeToken)) episodeToken = extractEpisodeToken(urlFileName);

        int episode = firstNumber(episodeToken);
        if (episode <= 0) {
            Matcher trailing = SIZE_TRAILING_EPISODE_PATTERN.matcher(artist);
            if (trailing.find()) episode = firstNumber(trailing.group(1));
        }

        int season = extractSeasonNumber(seasonSource);
        if (season <= 0) season = extractSeasonNumber(title);

        String year = extractYear(yearSource);
        if (isEmpty(year)) year = extractYear(title);

        List<String> aliases = buildAliases(
                title, canonicalTitle, displayTitle, originalTitle, seriesTitle
        );
        return new Identity(title, artist, fileName, canonicalTitle, displayTitle, seriesTitle,
                originalTitle, year, episodeToken, season, episode, aliases);
    }

    public static String extractEpisodeToken(String value) {
        if (isEmpty(value)) return "";
        String processed = preprocessEpisodeText(value);

        Matcher matcher = SEASON_EPISODE_PATTERN.matcher(processed);
        if (matcher.find()) return firstNonEmpty(matcher.group(2));

        matcher = CHINESE_EPISODE_PATTERN.matcher(processed);
        if (matcher.find()) return convertChineseNumberToArabic(matcher.group(1));

        matcher = EPISODE_PATTERN.matcher(processed);
        if (matcher.find()) {
            return matcher.group(1);
        }

        matcher = BRACKET_EPISODE_PATTERN.matcher(processed);
        if (matcher.find() && !isLikelyFileSize(
                processed, matcher.start(1), matcher.end(1))) {
            return matcher.group(1);
        }

        List<MatchCandidate> candidates = new ArrayList<>();
        matcher = STANDALONE_PATTERN.matcher(processed);
        while (matcher.find()) {
            String number = matcher.group(1);
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (isLikelyEpisodeNumber(processed, number, start, end)) {
                candidates.add(new MatchCandidate(
                        number, start, end,
                        calculatePriority(processed, number, start, end)
                ));
            }
        }

        matcher = FILENAME_EPISODE_PATTERN.matcher(processed);
        while (matcher.find()) {
            String number = matcher.group(1);
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (isLikelyEpisodeNumber(processed, number, start, end)) {
                candidates.add(new MatchCandidate(
                        number, start, end,
                        calculatePriority(processed, number, start, end) + 10
                ));
            }
        }

        if (!candidates.isEmpty()) {
            Collections.sort(candidates, (left, right) -> {
                int priority = Integer.compare(right.priority, left.priority);
                return priority != 0 ? priority : Integer.compare(right.start, left.start);
            });
            MatchCandidate best = candidates.get(0);
            return checkForSpecialPrefix(processed, best.number, best.start);
        }

        Set<String> possibleEpisodes = new LinkedHashSet<>();
        matcher = LAST_RESORT_PATTERN.matcher(processed);
        while (matcher.find()) {
            String number = matcher.group(1);
            if (isPureEpisodeNumber(
                    processed, number, matcher.start(1), matcher.end(1))) {
                possibleEpisodes.add(number);
            }
        }

        matcher = START_OR_SPACE_PATTERN.matcher(processed);
        while (matcher.find()) {
            String number = matcher.group(1);
            if (isPureEpisodeNumber(
                    processed, number, matcher.start(1), matcher.end(1))) {
                possibleEpisodes.add(number);
            }
        }

        matcher = ENDING_NUMBER_PATTERN.matcher(processed);
        while (matcher.find()) {
            String number = matcher.group(1);
            if (isPureEpisodeNumber(
                    processed, number, matcher.start(1), matcher.end(1))) {
                possibleEpisodes.add(number);
            }
        }

        for (String number : possibleEpisodes) {
            int numberValue = safeInt(number);
            if (numberValue >= 1 && numberValue <= 99) {
                int position = processed.indexOf(number);
                return position >= 0
                        ? checkForSpecialPrefix(processed, number, position) : number;
            }
        }
        if (!possibleEpisodes.isEmpty()) {
            String first = possibleEpisodes.iterator().next();
            int position = processed.indexOf(first);
            return position >= 0
                    ? checkForSpecialPrefix(processed, first, position) : first;
        }
        return "";
    }

    public static int parseEpisodeNumber(String value) {
        return firstNumber(extractEpisodeToken(value));
    }

    public static int parseSeasonNumber(String value) {
        return extractSeasonNumber(value);
    }

    public static String extractSearchTitle(String value) {
        if (isEmpty(value)) return "";
        String result = stripPlayingPrefix(value);
        result = QUALITY_PATTERN.matcher(result).replaceAll(" ");
        result = VERSION_PATTERN.matcher(result).replaceAll(" ");
        result = SEASON_PATTERN.matcher(result).replaceAll(" ");
        result = SEASON_EPISODE_PATTERN.matcher(result).replaceAll(" ");
        result = CHINESE_EPISODE_PATTERN.matcher(result).replaceAll(" ");
        result = EPISODE_PATTERN.matcher(result).replaceAll(" ");
        result = result.replaceAll("[\\[【(（].*?[\\]】)）]", " ");
        result = result.replaceAll("(?i)\\b(?:S\\d{1,2}|E\\d{1,4}|EP\\d{1,4})\\b", " ");
        result = result.replace('_', ' ');
        result = result.replaceAll("^[\\s._\\-—]+|[\\s._\\-—]+$", "");
        result = result.replaceAll("\\s{2,}", " ").trim();
        return stripExtension(result);
    }

    public static String normalizeForCompare(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public static String extractYear(String value) {
        if (isEmpty(value)) return "";
        Matcher matcher = YEAR_BEFORE_SEASON_PATTERN.matcher(value);
        if (matcher.find()) {
            int year = safeInt(matcher.group(1));
            if (year >= 1900 && year <= 2030) return String.valueOf(year);
        }
        matcher = YEAR_PATTERN.matcher(value);
        if (matcher.find()) return matcher.group();
        matcher = YEAR_BRACKET_PATTERN.matcher(value);
        if (matcher.find()) return matcher.group(1);
        matcher = YEAR_SPACE_PATTERN.matcher(value);
        if (matcher.find()) return matcher.group(1);
        return "";
    }

    private static String extractTitle(String title) {
        if (isEmpty(title)) return "";
        String cleaned = title.trim();
        int chineseStart = cleaned.indexOf('《');
        int chineseEnd = cleaned.indexOf('》');
        if (chineseStart >= 0 && chineseEnd > chineseStart) {
            cleaned = cleaned.substring(chineseStart + 1, chineseEnd);
        } else {
            int firstPipe = cleaned.indexOf('|');
            int secondPipe = firstPipe >= 0 ? cleaned.indexOf('|', firstPipe + 1) : -1;
            if (firstPipe >= 0 && secondPipe > firstPipe) {
                cleaned = cleaned.substring(firstPipe + 1, secondPipe);
            }
        }
        int bracket = earliestIndex(cleaned.indexOf('（'), cleaned.indexOf('('));
        if (bracket >= 0) cleaned = cleaned.substring(0, bracket);
        return cleaned.trim();
    }

    private static String extractSeriesName(String title) {
        if (isEmpty(title)) return "";
        Matcher matcher = SERIES_NAME_PATTERN.matcher(title);
        if (matcher.find()) {
            String series = matcher.group(1);
            if (series != null) {
                series = series.trim()
                        .replaceAll("[:：\\-—~～\\[\\]【】()（）]+$", "");
                if (!isEmpty(series)) return series;
            }
        }
        String[] parts = title.split("[:：\\-—~～]", 2);
        String first = parts[0].trim().replaceAll("\\s+\\d+$", "");
        if (!isEmpty(first)) return first;
        return extractSearchTitle(title);
    }

    private static int extractSeasonNumber(String value) {
        if (isEmpty(value)) return 0;
        Matcher combined = SEASON_EPISODE_PATTERN.matcher(value);
        if (combined.find()) {
            int season = parseNumber(combined.group(1));
            if (season > 0) return season;
        }
        Matcher matcher = SEASON_PATTERN.matcher(value);
        if (matcher.find()) {
            String number = firstNonEmpty(matcher.group(1), matcher.group(2));
            int season = parseNumber(number);
            if (season > 0) return season;
        }
        return 0;
    }

    private static String preprocessEpisodeText(String value) {
        String processed = stripPlayingPrefix(value);
        processed = SIZE_PATTERN.matcher(processed).replaceAll(" ");
        processed = QUALITY_PATTERN.matcher(processed).replaceAll(" ");
        processed = VERSION_PATTERN.matcher(processed).replaceAll(" ");
        processed = EXTENSION_PATTERN.matcher(processed).replaceAll(" ");
        processed = processed.replaceAll("\\s+", " ").trim();
        return isEmpty(processed) ? value : processed;
    }

    private static boolean isLikelyFileSize(String title, int numberStart, int numberEnd) {
        if (numberStart < 0 || numberEnd > title.length()) return false;
        if (numberStart > 0) {
            int start = numberStart;
            while (start > 0 && Character.isDigit(title.charAt(start - 1))) start--;
            if (start > 1) {
                String before = title.substring(Math.max(0, start - 3), start);
                if (before.matches(".*[0-9]\\.[0-9]*$") && numberEnd < title.length()) {
                    String after = title.substring(
                            numberEnd, Math.min(title.length(), numberEnd + 5));
                    if (after.matches("^(?:\\.?[0-9]*[GMK]B?\\b).*")) return true;
                }
            }
        }
        if (numberEnd < title.length()) {
            String after = title.substring(
                    numberEnd, Math.min(title.length(), numberEnd + 5));
            if (after.matches("^(?:\\.?[0-9]*[GMK]B?\\b).*")) return true;
        }
        return false;
    }

    private static boolean isLikelyEpisodeNumber(
            String title, String number, int start, int end) {
        int value = safeInt(number);
        if (value <= 0 || value > 999) return false;
        if (isLikelyFileSize(title, start, end)) return false;
        String context = getContext(title, start, end, 10);
        boolean hasEpisodeKeyword = context.matches(
                "(?i).*(ep|episode|集|话|章|回).*");
        boolean hasNonEpisodeKeyword = context.matches(
                "(?i).*(gb|mb|kb|size|大小|分辨率|fps|bitrate|码率).*");
        return hasEpisodeKeyword || !hasNonEpisodeKeyword;
    }

    private static boolean isPureEpisodeNumber(
            String title, String number, int start, int end) {
        if (!isLikelyEpisodeNumber(title, number, start, end)) return false;
        int value = safeInt(number);
        if (value < 1 || value > 999) return false;
        if (value <= 99) return true;
        String context = getContext(title, start, end, 20);
        return context.matches(
                "(?i).*(ep|episode|[第][0-9零一二三四五六七八九十百千万]+[集话章节回]).*");
    }

    private static int calculatePriority(
            String title, String number, int start, int end) {
        int priority = 0;
        int value = safeInt(number);
        if (value >= 1 && value <= 99) priority += 10;
        String before = start > 0
                ? title.substring(Math.max(0, start - 3), start) : "";
        String after = end < title.length()
                ? title.substring(end, Math.min(title.length(), end + 3)) : "";
        if (before.matches(".*[\\s\\[\\]()\\-._].*")) priority += 5;
        if (after.matches("^[\\.\\s\\[\\]()\\-].*")) priority += 5;
        if (!title.isEmpty()) priority += start * 100 / title.length();
        return priority;
    }

    private static String getContext(String title, int start, int end, int window) {
        int contextStart = Math.max(0, start - window);
        int contextEnd = Math.min(title.length(), end + window);
        return title.substring(contextStart, contextEnd);
    }

    private static String checkForSpecialPrefix(
            String title, String number, int startPosition) {
        if (isEmpty(title) || isEmpty(number) || startPosition <= 0) return number;
        String before = title.substring(0, startPosition).trim();
        String[] keywords = {"彩蛋", "预告", "采访", "特典", "花絮", "幕后"};
        for (String keyword : keywords) {
            if (before.endsWith(keyword)) return keyword + number;
        }
        return number;
    }

    private static List<String> buildAliases(String rawTitle, String... candidates) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (String candidate : candidates) {
            addAlias(aliases, candidate);
            if (containsCjk(candidate)) {
                addAlias(aliases, candidate.replaceAll("\\s+", ""));
            }
            if (candidate != null && candidate.indexOf('.') >= 0
                    && candidate.indexOf(' ') < 0) {
                addAlias(aliases, candidate.replace('.', ' '));
            }
        }
        addAlias(aliases, extractSearchTitle(rawTitle));
        return new ArrayList<>(aliases);
    }

    private static void addAlias(Set<String> aliases, String value) {
        if (isEmpty(value)) return;
        String alias = value.trim();
        if (alias.length() < 2) return;
        String normalized = normalizeForCompare(alias);
        if (normalized.length() < 2) return;
        for (String existing : aliases) {
            if (normalizeForCompare(existing).equals(normalized)) return;
        }
        aliases.add(alias);
    }

    private static boolean containsCjk(String value) {
        return value != null && value.matches(".*[\\u3400-\\u9FFF].*");
    }

    private static String extractUrlFileName(String url) {
        if (isEmpty(url)) return "";
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        int hash = path.indexOf('#');
        if (hash >= 0) path = path.substring(0, hash);
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        return stripExtension(path);
    }

    private static String stripPlayingPrefix(String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.startsWith("正在播放：")) result = result.substring("正在播放：".length());
        return result.trim();
    }

    private static String stripExtension(String value) {
        if (isEmpty(value)) return "";
        return EXTENSION_PATTERN.matcher(value.trim()).replaceAll("");
    }

    private static int parseNumber(String value) {
        if (isEmpty(value)) return 0;
        int parsed = safeInt(value);
        return parsed > 0 ? parsed : safeInt(convertChineseNumberToArabic(value));
    }

    private static int firstNumber(String value) {
        if (isEmpty(value)) return 0;
        Matcher matcher = FIRST_NUMBER_PATTERN.matcher(value);
        return matcher.find() ? safeInt(matcher.group(1)) : 0;
    }

    private static int safeInt(String value) {
        if (isEmpty(value)) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String convertChineseNumberToArabic(String value) {
        if (isEmpty(value)) return "0";
        if (!value.matches("[零一二三四五六七八九十百千万]+")) {
            return value;
        }
        int result = 0;
        int current = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int digit = "零一二三四五六七八九".indexOf(ch);
            if (digit >= 0) {
                current = digit;
                continue;
            }
            int unit = ch == '十' ? 10 : ch == '百' ? 100 : ch == '千' ? 1000 : 10000;
            result += (current == 0 ? 1 : current) * unit;
            current = 0;
        }
        return String.valueOf(result + current);
    }

    private static int earliestIndex(int first, int second) {
        if (first < 0) return second;
        if (second < 0) return first;
        return Math.min(first, second);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!isEmpty(value)) return value;
        }
        return "";
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class MatchCandidate {
        final String number;
        final int start;
        final int end;
        final int priority;

        MatchCandidate(String number, int start, int end, int priority) {
            this.number = number;
            this.start = start;
            this.end = end;
            this.priority = priority;
        }
    }
}
