package com.github.catvod.spider;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto 去片头尾：TMDB 负责解析剧集，IntroDB/AniSkip 负责提供时间点。
 */
public final class AutoSkipService {

    private static final String PREFS_NAME = "danmaku_auto_skip_cache_v4";
    private static final String TMDB_BASE = "https://api.themoviedb.org/3";
    private static final String TMDB_FALLBACK_BASE = "https://api.tmdb.org/3";
    private static final String INTRODB_SEGMENTS = "https://api.introdb.app/segments";
    private static final String ANILIST_URL = "https://graphql.anilist.co";
    private static final String ANISKIP_BASE = "https://api.aniskip.com/v2/skip-times/";
    private static final long SUCCESS_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long FAILURE_TTL_MS = 15L * 1000;
    private static final int PRELOAD_AHEAD = 2;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "DanmakuAutoSkipSource");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> IN_FLIGHT =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile long lastCachePruneAt;

    private static final Pattern SEASON_EPISODE_PATTERN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:S(?:eason)?\\s*(\\d{1,2}))?[ ._-]*"
                    + "E(?:P(?:ISODE)?)?\\s*(\\d{1,4})(?![0-9])");
    private static final Pattern SEASON_PATTERN = Pattern.compile(
            "(?:第\\s*([0-9零一二三四五六七八九十]+)\\s*季)|(?i)(?:S|SEASON)\\s*(\\d{1,2})");
    private static final Pattern EPISODE_PATTERN = Pattern.compile(
            "(?:第\\s*([0-9零一二三四五六七八九十百]+)\\s*[集话章回])|"
                    + "(?i)(?<![A-Za-z0-9])(?:EP?|EPISODE)\\s*([0-9]{1,4})(?![0-9])|"
                    + "(?<!\\d)([0-9]{1,4})\\s*[集话章回]");
    private static final Pattern QUALITY_PATTERN = Pattern.compile(
            "(?i)[\\[【(（]?(?:HDR10\\+?|HDR VIVID|DOLBY[ .-]?VISION|杜比视界|"
                    + "DOLBY[ .-]?ATMOS|杜比全景声|IMAX ENHANCED|HDR|4K|8K|2K|"
                    + "UHD|FHD|HD|SD|2160P|1080P|720P|480P|"
                    + "HEVC|H\\.?26[45]|X26[45]|AVC|AV1|60FPS|60帧|"
                    + "高码率|高码|AAC|AC3|DTS|FLAC|ATMOS|"
                    + "WEB[- .]?DL|WEBRIP|BLURAY|REMUX|SDR|"
                    + "国粤双语|国语|粤语|中字|简中|繁中|内封|外挂|"
                    + "无水印|未删减|完整版|臻彩|臻享)[\\]】)）]?");
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:V|VER)\\.?\\s*\\d+(?:\\.\\d+)?(?![A-Za-z0-9])");

    public static class Markers {
        public long introStartMs = -1;
        public long introEndMs = -1;
        public long outroStartMs = -1;
        public String imdbId = "";
        public String tmdbTitle = "";
        public String source = "";
        public String error = "";

        public boolean hasIntro() {
            return introEndMs > Math.max(0, introStartMs) && introEndMs > introStartMs;
        }

        public boolean hasOutro() {
            return outroStartMs > 0;
        }

        public boolean hasAny() {
            return hasIntro() || hasOutro();
        }
    }

    public interface Callback {
        void onResult(Markers markers);
    }

    private static class Candidate {
        long id;
        String mediaType;
        String title;
        String originalTitle;
        String date;
        boolean animation;
    }

    private AutoSkipService() {
    }

    public static boolean refreshAsync(Context context, ChapterManager.MediaInfo media,
                                       DanmakuConfig config, Callback callback) {
        if (context == null || media == null || config == null) return false;
        String key = makeCacheKey(media);
        if (TextUtils.isEmpty(key) || !IN_FLIGHT.add(key)) return false;

        Context appContext = context.getApplicationContext();
        String tmdbKey = config.getTmdbApiKey();
        String language = config.getTmdbLanguage();
        EXECUTOR.execute(() -> {
            Markers result;
            try {
                pruneExpiredCache(appContext);
                result = readCache(appContext, key);
                if (result == null) {
                    result = fetch(appContext, media, tmdbKey, language);
                    writeCache(appContext, key, result);
                }
            } catch (Throwable e) {
                result = new Markers();
                result.error = "自动片头查询异常: " + safeMessage(e);
                DanmakuSpider.log(result.error);
            } finally {
                IN_FLIGHT.remove(key);
            }
            final Markers finalResult = result;
            schedulePrefetch(appContext, media, config, finalResult);
            MAIN.post(() -> {
                if (callback != null) callback.onResult(finalResult);
            });
        });
        return true;
    }

    public static void clearCache(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static void pruneExpiredCache(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastCachePruneAt < 24L * 60 * 60 * 1000) return;
        lastCachePruneAt = now;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith("seg_") || !(entry.getValue() instanceof String)) continue;
            try {
                JSONObject json = new JSONObject((String) entry.getValue());
                if (json.optLong("expires", 0) <= now) {
                    if (editor == null) editor = prefs.edit();
                    editor.remove(entry.getKey());
                }
            } catch (Exception ignored) {
                if (editor == null) editor = prefs.edit();
                editor.remove(entry.getKey());
            }
        }
        if (editor != null) editor.apply();
    }

    public static boolean isValidKey(String key) {
        if (TextUtils.isEmpty(key)) return false;
        String value = key.trim();
        return value.matches("(?i)[0-9a-f]{32}") || value.startsWith("eyJ");
    }

    public static String getKeyTypeLabel(String key) {
        if (TextUtils.isEmpty(key)) return "未配置";
        return key.trim().startsWith("eyJ") ? "v4 Bearer Token" : "v3 API Key";
    }

    public static String maskKey(String key) {
        if (TextUtils.isEmpty(key)) return "未配置";
        String value = key.trim();
        if (value.length() <= 8) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    public static String testApiKey(String key) {
        if (!isValidKey(key)) return "Key 格式不正确";
        try {
            String body = tmdbGet("/authentication", key, null);
            JSONObject json = new JSONObject(body);
            if (json.optBoolean("success")) return "TMDB 连接成功";
            int statusCode = json.optInt("status_code", 0);
            if (statusCode == 7 || statusCode == 3) return "TMDB Key 无效或已过期";
            String message = json.optString("status_message", "");
            return TextUtils.isEmpty(message) ? "TMDB 验证失败" : message;
        } catch (Exception e) {
            return "TMDB 验证失败: " + safeMessage(e);
        }
    }

    private static Markers fetch(Context context, ChapterManager.MediaInfo media,
                                 String apiKey, String language) {
        Markers result = new Markers();
        if (!isValidKey(apiKey)) {
            result.error = "TMDB Key 未配置或格式错误";
            return result;
        }
        String title = normalizeTitle(media.title);
        if (TextUtils.isEmpty(title)) title = normalizeTitle(media.artist);
        if (TextUtils.isEmpty(title)) {
            result.error = "无法识别当前片名";
            return result;
        }

        int[] seasonEpisode = parseSeasonEpisode(media);
        int season = seasonEpisode[0];
        int episode = seasonEpisode[1];
        Candidate candidate = searchTmdb(title, apiKey, language, episode <= 0);
        if (candidate == null || candidate.id <= 0) {
            String keyStatus = testApiKey(apiKey);
            result.error = keyStatus.contains("成功")
                    ? "TMDB 未匹配到: " + title : keyStatus;
            return result;
        }
        result.tmdbTitle = candidate.title;

        String imdbId = getImdbId(candidate, apiKey);
        result.imdbId = imdbId;
        if (TextUtils.isEmpty(imdbId)) {
            String keyStatus = testApiKey(apiKey);
            if (!keyStatus.contains("成功")) {
                result.error = keyStatus;
                return result;
            }
        }
        if (!TextUtils.isEmpty(imdbId)) {
            readIntroDb(result, imdbId, season, episode, "movie".equals(candidate.mediaType));
        }
        if (!result.hasAny() && !"movie".equals(candidate.mediaType)) {
            readAniSkip(result, title, season, episode, media.durationMs);
        }
        if (!result.hasAny()) {
            if (!TextUtils.isEmpty(result.error)) return result;
            result.error = "没有可用的片头片尾数据";
        }
        return result;
    }

    private static void schedulePrefetch(Context context, ChapterManager.MediaInfo media,
                                         DanmakuConfig config, Markers current) {
        if (context == null || media == null || config == null || current == null
                || TextUtils.isEmpty(current.imdbId)) return;
        if (!config.isAutoSkipEnabled()) return;

        String title = normalizeTitle(media.title);
        if (TextUtils.isEmpty(title)) title = normalizeTitle(media.artist);
        int[] seasonEpisode = parseSeasonEpisode(media);
        final int season = seasonEpisode[0];
        final int currentEpisode = seasonEpisode[1];
        if (TextUtils.isEmpty(title) || currentEpisode <= 0) return;

        for (int offset = 1; offset <= PRELOAD_AHEAD; offset++) {
            final int episode = currentEpisode + offset;
            final String titleForTask = title;
            String cacheKey = makeCacheKey(title, season, episode);
            if (TextUtils.isEmpty(cacheKey) || readCache(context, cacheKey) != null) continue;
            if (!IN_FLIGHT.add(cacheKey)) continue;
            EXECUTOR.execute(() -> {
                try {
                    if (readCache(context, cacheKey) != null) return;
                    Markers prefetched = new Markers();
                    prefetched.imdbId = current.imdbId;
                    prefetched.tmdbTitle = current.tmdbTitle;
                    readIntroDb(prefetched, current.imdbId, season, episode, false);
                    if (!prefetched.hasAny()) {
                        readAniSkip(prefetched, titleForTask, season, episode, media.durationMs);
                    }
                    if (!prefetched.hasAny() && TextUtils.isEmpty(prefetched.error)) {
                        prefetched.error = "预加载无可用数据";
                    }
                    writeCache(context, cacheKey, prefetched);
                    if (prefetched.hasAny()) {
                        DanmakuSpider.log("Auto 预加载完成: S" + season + "E" + episode
                                + " " + prefetched.source);
                    }
                } catch (Throwable e) {
                    DanmakuSpider.log("Auto 预加载失败: S" + season + "E" + episode
                            + " " + safeMessage(e));
                } finally {
                    IN_FLIGHT.remove(cacheKey);
                }
            });
        }
    }

    private static Candidate searchTmdb(String title, String apiKey, String language,
                                        boolean movieOnly) {
        Candidate candidate = searchTmdbType(title, apiKey, language, movieOnly ? "movie" : "tv");
        if (candidate == null && !movieOnly) {
            candidate = searchTmdbType(title, apiKey, language, "movie");
        }
        return candidate;
    }

    private static Candidate searchTmdbType(String title, String apiKey, String language,
                                            String type) {
        try {
            String body = tmdbGet("/search/" + type,
                    apiKey, "query=" + encode(title) + "&language=" + encode(language)
                            + "&include_adult=false&page=1");
            JSONArray results = new JSONObject(body).optJSONArray("results");
            if (results == null || results.length() == 0) return null;

            String normalizedQuery = normalizeForCompare(title);
            Candidate best = null;
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null || item.optLong("id") <= 0) continue;
                Candidate candidate = new Candidate();
                candidate.id = item.optLong("id");
                candidate.mediaType = type;
                candidate.title = "movie".equals(type)
                        ? item.optString("title") : item.optString("name");
                candidate.originalTitle = "movie".equals(type)
                        ? item.optString("original_title") : item.optString("original_name");
                candidate.date = "movie".equals(type)
                        ? item.optString("release_date") : item.optString("first_air_date");
                JSONArray genres = item.optJSONArray("genre_ids");
                if (genres != null) {
                    for (int j = 0; j < genres.length(); j++) {
                        if (genres.optInt(j) == 16) {
                            candidate.animation = true;
                            break;
                        }
                    }
                }
                if (best == null) best = candidate;
                String normalizedName = normalizeForCompare(candidate.title);
                String normalizedOriginal = normalizeForCompare(candidate.originalTitle);
                if (normalizedQuery.equals(normalizedName)
                        || normalizedQuery.equals(normalizedOriginal)) {
                    return candidate;
                }
            }
            return best;
        } catch (Exception e) {
            DanmakuSpider.log("TMDB 搜索失败: " + safeMessage(e));
            return null;
        }
    }

    private static String getImdbId(Candidate candidate, String apiKey) {
        try {
            String body = tmdbGet("/" + candidate.mediaType + "/" + candidate.id
                    + "/external_ids", apiKey, null);
            return new JSONObject(body).optString("imdb_id", "").trim();
        } catch (Exception e) {
            DanmakuSpider.log("TMDB 外部 ID 查询失败: " + safeMessage(e));
            return "";
        }
    }

    private static void readIntroDb(Markers result, String imdbId, int season,
                                    int episode, boolean movie) {
        StringBuilder url = new StringBuilder(INTRODB_SEGMENTS)
                .append("?imdb_id=").append(encode(imdbId));
        if (movie) {
            url.append("&is_movie=true");
        } else {
            url.append("&season=").append(Math.max(1, season))
                    .append("&episode=").append(Math.max(1, episode))
                    .append("&is_movie=false");
        }
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JSONObject json = new JSONObject(OkHttp.string(url.toString(), 12000));
                JSONObject intro = json.optJSONObject("intro");
                JSONObject outro = json.optJSONObject("outro");
                if (intro == null && outro == null && attempt == 0) {
                    Thread.sleep(300);
                    continue;
                }
                applySegment(result, intro, "IntroDB");
                if (outro != null) {
                    long start = outro.optLong("start_ms", -1);
                    if (start > 0) result.outroStartMs = start;
                }
                return;
            } catch (Exception e) {
                lastError = e;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        result.error = "IntroDB 请求失败: " + safeMessage(lastError);
    }

    private static void applySegment(Markers result, JSONObject segment, String source) {
        if (segment == null) return;
        long start = segment.optLong("start_ms", -1);
        long end = segment.optLong("end_ms", -1);
        if (start < 0 || end <= start) return;
        result.introStartMs = start;
        result.introEndMs = end;
        result.source = source;
    }

    private static void readAniSkip(Markers result, String title, int season,
                                    int episode, long durationMs) {
        try {
            int malId = searchMalId(title);
            if (malId <= 0 || episode <= 0) return;
            int length = durationMs > 0 ? (int) Math.max(1, Math.round(durationMs / 1000.0)) : 0;
            String url = ANISKIP_BASE + malId + "/" + episode
                    + "?types%5B%5D=op&types%5B%5D=ed&types%5B%5D=mixed-op"
                    + "&types%5B%5D=mixed-ed&episodeLength=" + length;
            JSONObject json = new JSONObject(OkHttp.string(url, 12000));
            if (!json.optBoolean("found")) return;
            JSONArray results = json.optJSONArray("results");
            if (results == null) return;
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                JSONObject interval = item == null ? null : item.optJSONObject("interval");
                if (interval == null) continue;
                String skipType = item.optString("skipType", "");
                long start = Math.round(interval.optDouble("startTime", -1) * 1000);
                long end = Math.round(interval.optDouble("endTime", -1) * 1000);
                if (end <= start) continue;
                if ("op".equals(skipType) || "mixed-op".equals(skipType)) {
                    result.introStartMs = start;
                    result.introEndMs = end;
                    result.source = "AniSkip";
                } else if ("ed".equals(skipType) || "mixed-ed".equals(skipType)) {
                    result.outroStartMs = start;
                    result.source = TextUtils.isEmpty(result.source) ? "AniSkip" : result.source;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static int searchMalId(String title) {
        try {
            JSONObject variables = new JSONObject().put("search", title);
            JSONObject payload = new JSONObject()
                    .put("query", "query($search:String){Page(perPage:5){media(search:$search,type:ANIME){idMal title{romaji english native} startDate{year}}}}")
                    .put("variables", variables);
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "application/json");
            String body = OkHttp.post(ANILIST_URL, payload.toString(), headers).getBody();
            JSONArray media = new JSONObject(body).optJSONObject("data")
                    .optJSONObject("Page").optJSONArray("media");
            if (media == null) return 0;
            for (int i = 0; i < media.length(); i++) {
                int id = media.optJSONObject(i).optInt("idMal", 0);
                if (id > 0) return id;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String tmdbGet(String path, String key, String extraQuery) {
        boolean bearer = key != null && key.trim().startsWith("eyJ");
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        if (bearer) headers.put("Authorization", "Bearer " + key.trim());

        String body = OkHttp.string(buildTmdbUrl(TMDB_BASE, path, key, extraQuery),
                null, headers, 12000);
        if (!TextUtils.isEmpty(body) && !body.trim().equals("{}")) return body;
        return OkHttp.string(buildTmdbUrl(TMDB_FALLBACK_BASE, path, key, extraQuery),
                null, headers, 12000);
    }

    private static String buildTmdbUrl(String base, String path, String key, String extraQuery) {
        boolean bearer = key != null && key.trim().startsWith("eyJ");
        StringBuilder url = new StringBuilder(base).append(path);
        boolean hasQuery = extraQuery != null && !extraQuery.isEmpty();
        if (!bearer || hasQuery) url.append('?');
        if (!bearer) {
            url.append("api_key=").append(encode(key));
            if (hasQuery) url.append('&');
        }
        if (hasQuery) url.append(extraQuery);
        return url.toString();
    }

    private static Markers readCache(Context context, String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(cachePrefKey(key), null);
        if (TextUtils.isEmpty(raw)) return null;
        try {
            JSONObject json = new JSONObject(raw);
            if (json.optLong("expires", 0) <= System.currentTimeMillis()) {
                prefs.edit().remove(cachePrefKey(key)).apply();
                return null;
            }
            Markers result = new Markers();
            result.introStartMs = json.optLong("introStart", -1);
            result.introEndMs = json.optLong("introEnd", -1);
            result.outroStartMs = json.optLong("outroStart", -1);
            result.imdbId = json.optString("imdbId", "");
            result.tmdbTitle = json.optString("tmdbTitle", "");
            result.source = json.optString("source", "");
            result.error = json.optString("error", "");
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(Context context, String key, Markers value) {
        if (context == null || value == null) return;
        try {
            long ttl = value.hasAny() ? SUCCESS_TTL_MS : FAILURE_TTL_MS;
            JSONObject json = new JSONObject()
                    .put("expires", System.currentTimeMillis() + ttl)
                    .put("introStart", value.introStartMs)
                    .put("introEnd", value.introEndMs)
                    .put("outroStart", value.outroStartMs)
                    .put("imdbId", value.imdbId)
                    .put("tmdbTitle", value.tmdbTitle)
                    .put("source", value.source)
                    .put("error", value.error);
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(cachePrefKey(key), json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static String makeCacheKey(ChapterManager.MediaInfo media) {
        String title = normalizeTitle(media.title);
        if (TextUtils.isEmpty(title)) title = normalizeTitle(media.artist);
        int[] seasonEpisode = parseSeasonEpisode(media);
        return makeCacheKey(title, seasonEpisode[0], seasonEpisode[1]);
    }

    private static String makeCacheKey(String title, int season, int episode) {
        if (TextUtils.isEmpty(title) || episode <= 0) return "";
        return normalizeForCompare(title) + "|" + season + "|" + episode;
    }

    private static String cachePrefKey(String key) {
        return "seg_" + Integer.toHexString(key.hashCode());
    }

    private static int[] parseSeasonEpisode(ChapterManager.MediaInfo media) {
        String metadata = nullToEmpty(media.title) + " " + nullToEmpty(media.artist);
        int[] parsed = findSeasonEpisode(metadata);
        if (!TextUtils.isEmpty(media.url)) {
            String path = media.url;
            int query = path.indexOf('?');
            if (query >= 0) path = path.substring(0, query);
            int slash = path.lastIndexOf('/');
            if (slash >= 0) path = path.substring(slash + 1);
            if (parsed[1] <= 0) {
                int[] fromUrl = findSeasonEpisode(path);
                if (fromUrl[1] > 0) {
                    parsed = new int[]{
                            fromUrl[0] > 0 ? fromUrl[0] : parsed[0],
                            fromUrl[1]
                    };
                }
            }
        }
        return new int[]{Math.max(1, parsed[0]), Math.max(0, parsed[1])};
    }

    private static int[] findSeasonEpisode(String text) {
        int season = parseChineseOrNumber(findGroup(SEASON_PATTERN, text));
        int episode = 0;
        Matcher combined = SEASON_EPISODE_PATTERN.matcher(text);
        if (combined.find()) {
            int parsedSeason = parseChineseOrNumber(combined.group(1));
            if (parsedSeason > 0) season = parsedSeason;
            episode = parseChineseOrNumber(combined.group(2));
        }
        if (episode <= 0) {
            Matcher matcher = EPISODE_PATTERN.matcher(text);
            if (matcher.find()) {
                episode = parseChineseOrNumber(firstNonEmpty(
                        matcher.group(1), matcher.group(2), matcher.group(3)));
            }
        }
        return new int[]{season, episode};
    }

    private static String normalizeTitle(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String title = value;
        int slash = title.lastIndexOf('/');
        if (slash >= 0) title = title.substring(slash + 1);
        int dot = title.lastIndexOf('.');
        if (dot > 0 && title.length() - dot <= 8) title = title.substring(0, dot);
        title = QUALITY_PATTERN.matcher(title).replaceAll(" ");
        title = VERSION_PATTERN.matcher(title).replaceAll(" ");
        title = SEASON_EPISODE_PATTERN.matcher(title).replaceAll(" ");
        title = EPISODE_PATTERN.matcher(title).replaceAll(" ");
        title = title.replaceAll("[\\[【(（].*?[\\]】)）]", " ");
        title = title.replaceAll("(?i)\\b(?:S\\d{1,2}|E\\d{1,4}|EP\\d{1,4})\\b", " ");
        title = title.replaceAll("[_]+", " ");
        title = title.replaceAll("^[\\s._\\-—]+|[\\s._\\-—]+$", "");
        title = title.replaceAll("\\s{2,}", " ").trim();
        return title;
    }

    private static String normalizeForCompare(String value) {
        if (value == null) return "";
        return value.toLowerCase().replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String findGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return "";
        for (int i = 1; i <= matcher.groupCount(); i++) {
            if (!TextUtils.isEmpty(matcher.group(i))) return matcher.group(i);
        }
        return "";
    }

    private static int parseChineseOrNumber(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return chineseNumber(value.trim());
        }
    }

    private static int chineseNumber(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        String digits = "零一二三四五六七八九";
        if (value.length() == 1) {
            int index = digits.indexOf(value.charAt(0));
            return index >= 0 ? index : 0;
        }
        int result = 0;
        int current = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int digit = digits.indexOf(ch);
            if (digit >= 0) {
                current = digit;
            } else if (ch == '十') {
                result += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (ch == '百') {
                result += (current == 0 ? 1 : current) * 100;
                current = 0;
            }
        }
        return result + current;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable throwable) {
        if (throwable == null) return "未知错误";
        String message = throwable.getMessage();
        return TextUtils.isEmpty(message) ? throwable.getClass().getSimpleName() : message;
    }
}
