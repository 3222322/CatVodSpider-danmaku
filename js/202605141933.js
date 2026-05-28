// ===================== API配置（集中管理，便于维护） =====================
const TMDB_API_KEY = process.env.TMDB_API_KEY || 'c2d8bcc1ff01b54ee5039d5b70f2b67e';  // TMDB API密钥，用于获取电影/电视剧详情和搜索
const TMDB_IMAGE_BASE = 'https://image.tmdb.org/t/p/w500';  // TMDB图片基础URL，用于拼接海报等图片路径
const TVMAZE_API_BASE = 'https://api.tvmaze.com';  // TVmaze API基础URL，用于获取电视剧集数信息
const DOUBAN_API_BASE = 'https://m.douban.com/rexxar/api/v2';  // 豆瓣移动端API基础URL，用于获取热门影视数据

/*
 * 🎬 豆瓣纯净目录 + TMDB 动漫榜（TMDB集数版 - 修复多季匹配）
 * ===================================================
 * ✅ 缓存 LRU 限制（最多 500 条）
 * ✅ 缓存 key 稳定排序
 * ✅ 分页 pagecount 正确（不无限翻页）
 * ✅ runningFetches 超时保护
 * ✅ 定时器防重复注册（全局引用）
 * ✅ Top250 使用 cheerio 解析（需安装 cheerio）
 * ✅ 请求超时兜底 & AbortController
 * ✅ 所有异常返回空列表结构
 * ✅ 完全兼容 T4 / TVBox 数据源格式
 * ✅ TMDB 动漫集数显示（全部获取）
 * ✅ TMDB 限流保护 & 分批请求
 * 🔧 修复：剧集集数全部使用TMDB数据
 * 🔧 修复：搜索时自动去除“第X季”后缀
 * ⚡ 新增：TVmaze 集数优先，实时更新更快
 * 🛡️ 增强：TVmaze独立熔断、豆瓣降级缓存、TMDB Key环境变量
 */

const axios = require("axios");
const cheerio = require("cheerio");

// ===================== 日志 =====================
let log = {
    info: (msg) => console.log(`[INFO] ${msg}`),
    error: (msg) => console.error(`[ERROR] ${msg}`),
    warn: (msg) => console.warn(`[WARN] ${msg}`)
};

const init = async (server) => {
    if (log.init) return;
    if (server && server.log) {
        log.info = (...args) => server.log.info(args.join(' '));
        log.error = (...args) => server.log.error(args.join(' '));
        log.warn = (...args) => server.log.warn(args.join(' '));
    }
    log.init = true;
};

// ===================== 工具函数 =====================
const stableStringify = (obj) => {
    if (obj === null || typeof obj !== 'object') return JSON.stringify(obj);
    if (Array.isArray(obj)) return '[' + obj.map(stableStringify).join(',') + ']';
    const sortedKeys = Object.keys(obj).sort();
    return '{' + sortedKeys.map(k => JSON.stringify(k) + ':' + stableStringify(obj[k])).join(',') + '}';
};

const getPageCacheKey = (id, page, filters) => `${id}_${page}_${stableStringify(filters || {})}`;

// ===================== 评分文案 =====================
const getScoreText = (item, type) => {
    if (item.rating?.value) return `${item.rating.value.toFixed(1)}分`;
    if (type === 'movie') return '新片';
    if (type === 'tv') return '新剧';
    if (type === 'show') return '新综';
    return '上新';
};

// ===================== TMDB 集数提取 =====================
const getTmdbEpisodeText = (item) => {
    const status = item.status;
    const totalEpisodes = item.number_of_episodes;
    const lastEpisode = item.last_episode_to_air;
    const nextEpisode = item.next_episode_to_air;

    if (status === 'Ended' || status === 'Canceled') {
        if (totalEpisodes) return `(全${totalEpisodes}集)`;
        return '(已完结)';
    }

    if (status === 'Returning Series') {
        if (nextEpisode?.episode_number && nextEpisode.episode_number > 1) {
            return `(更至${nextEpisode.episode_number - 1}集)`;
        }
        if (lastEpisode?.episode_number) {
            return `(更至${lastEpisode.episode_number}集)`;
        }
        if (totalEpisodes) return `(共${totalEpisodes}集)`;
        return '(连载中)';
    }

    if (totalEpisodes) return `(共${totalEpisodes}集)`;
    return '';
};

// ===================== 基础请求客户端 =====================
const baseRequestClient = async (url, options = {}) => {
    const timeout = options.timeout || 15000;
    let controller, timeoutId;
    if (typeof AbortController !== 'undefined') {
        controller = new AbortController();
        timeoutId = setTimeout(() => controller.abort(), timeout);
    } else {
        timeoutId = setTimeout(() => {}, timeout); // 仅用于清理，依赖 axios timeout
    }

    try {
        const res = await axios({
            method: options.method || 'GET',
            url,
            headers: {
                "Accept": "*/*",
                "Accept-Encoding": "gzip, deflate, br",
                "Connection": "keep-alive",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                ...(options.headers || {})
            },
            data: options.body || undefined,
            timeout: timeout,
            responseType: options.responseType || 'json',
            params: options.params,
            signal: controller ? controller.signal : undefined
        });
        clearTimeout(timeoutId);
        return res.data;
    } catch (e) {
        clearTimeout(timeoutId);
        throw e;
    }
};

const requestWithRetry = async (url, options = {}, retries = 2, delay = 1000) => {
    for (let attempt = 0; attempt <= retries; attempt++) {
        try {
            return await baseRequestClient(url, options);
        } catch (e) {
            const isLast = attempt === retries;
            const status = e.response?.status;
            const isAbort = e.name === 'AbortError';
            const shouldRetry = (!status || status >= 500 || isAbort) && status !== 429;
            if (isLast || !shouldRetry) {
                log.error(`请求最终失败: ${url}, 状态: ${status || (isAbort ? '超时' : '网络错误')}`);
                return null;
            }
            log.warn(`请求失败，${delay}ms 后重试 (${attempt + 1}/${retries}): ${url}`);
            await new Promise(r => setTimeout(r, delay));
            delay *= 2;
        }
    }
};

// ===================== 腾讯 Banner =====================
const fetchBanner = async () => {
    try {
        const bannerUrl = "https://pbaccess.video.qq.com/trpc.vector_layout.page_view.PageService/getPage?video_appid=3000010&vversion_platform=2";
        const requestData = {
            page_params: {
                page_type: "channel", page_id: "100113", scene: "channel",
                new_mark_label_enabled: "1", vl_to_mvl: "",
                free_watch_trans_info: "{\"ad_frequency_control_time_list\":{}}",
                ad_exp_ids: "100000",
                ams_cookies: "lv_play_index=26; o_minduid=CpGFdExDeM8uP-XHCyma_0PzurMADpcf; appuser=83C1297D3AE9DEFF",
                ad_trans_data: "{\"game_sessions\":[]}",
                skip_privacy_types: "0", support_click_scan: "1"
            },
            page_bypass_params: {
                params: { platform_id: "2", caller_id: "3000010", data_mode: "default", user_mode: "default", specified_strategy: "", page_type: "channel", page_id: "100113", scene: "channel", new_mark_label_enabled: "1" },
                scene: "channel", app_version: "", abtest_bypass_id: "aa836e91e1411155"
            },
            page_context: null
        };
        const data = await requestWithRetry(bannerUrl, {
            method: 'POST', body: JSON.stringify(requestData),
            headers: { 'Content-Type': 'application/json' }
        });
        if (!data?.data?.CardList) return [];
        const banner = [];
        for (const cardList of data.data.CardList) {
            if (cardList.type === "pc_carousel" && cardList.children_list?.list?.cards) {
                for (const card of cardList.children_list.list.cards) {
                    if (card.params?.pic_ori_2880x900) {
                        banner.push({
                            title: card.params.title || card.params.title_pc || "",
                            subtitle: card.params.stitle_pc || card.params.material_video_subtitle || "",
                            backgroundImage: card.params.pic_ori_2880x900,
                            genre: card.params.main_genre || "",
                            description: card.params.rec_normal_reason || ""
                        });
                        if (banner.length >= 5) break;
                    }
                }
                if (banner.length >= 5) break;
            }
        }
        return banner;
    } catch (e) {
        log.warn(`获取Banner失败: ${e.message}`);
        return [];
    }
};

// ===================== 筛选器配置 =====================
const filterConfig = {
    "movie": [
        { key: "category", name: "选择", init: "热门", value: [{ n: "热门", v: "热门" }, { n: "最新", v: "最新" }, { n: "豆瓣高分", v: "豆瓣高分" }, { n: "冷门佳片", v: "冷门佳片" }] },
        { key: "type", name: "地区", init: "全部", value: [{ n: "全部", v: "全部" }, { n: "华语", v: "华语" }, { n: "欧美", v: "欧美" }, { n: "韩国", v: "韩国" }, { n: "日本", v: "日本" }] }
    ],
    "tv": [
        { key: "type", name: "类型", init: "tv", value: [{ n: "综合", v: "tv" }, { n: "国产剧", v: "tv_domestic" }, { n: "欧美剧", v: "tv_american" }, { n: "日剧", v: "tv_japanese" }, { n: "韩剧", v: "tv_korean" }, { n: "动漫", v: "tv_animation" }, { n: "纪录片", v: "tv_documentary" }] }
    ],
    "show": [
        { key: "type", name: "类型", init: "show", value: [{ n: "综合", v: "show" }, { n: "国内", v: "show_domestic" }, { n: "国外", v: "show_foreign" }] }
    ],
    "top_250": [],
    "anime_tmdb": [
        {
            key: "tmdb_year", name: "年份", init: "all",
            value: [{ n: "全部", v: "all" }, ...Array.from({ length: 10 }, (_, i) => ({ n: `${new Date().getFullYear() - i}`, v: `${new Date().getFullYear() - i}` }))]
        },
        {
            key: "tmdb_region", name: "地区", init: "cn",
            value: [{ n: "全部", v: "all" }, { n: "中国", v: "cn" }, { n: "日本", v: "jp" }, { n: "美国", v: "us" }, { n: "其他", v: "other" }]
        },
        {
            key: "tmdb_sort", name: "榜单", init: "hot_following",
            value: [{ n: "全球趋势", v: "global_trending" }, { n: "高热追更", v: "hot_following" }, { n: "最新首播", v: "latest_premiere" }, { n: "热门口碑", v: "popular_reputation" }]
        }
    ]
};

// ===================== 缓存 LRU =====================
class LRUCache {
    constructor(maxSize = 500, ttl = 30 * 60 * 1000) {
        this.maxSize = maxSize;
        this.ttl = ttl;
        this.cache = new Map();
    }

    get(key) {
        const entry = this.cache.get(key);
        if (!entry) return undefined;
        const now = Date.now();
        if (now - entry.lastAccess > this.ttl) {
            this.cache.delete(key);
            return undefined;
        }
        // 刷新访问时间并移到末尾
        this.cache.delete(key);
        entry.lastAccess = now;
        this.cache.set(key, entry);
        return entry.data;
    }

    set(key, data) {
        if (this.cache.has(key)) this.cache.delete(key);
        if (this.cache.size >= this.maxSize) {
            const oldestKey = this.cache.keys().next().value;
            this.cache.delete(oldestKey);
        }
        this.cache.set(key, { data, lastAccess: Date.now() });
    }

    cleanExpired() {
        const now = Date.now();
        // 利用 LRU 特性：最近使用的在尾部，头部是最久未使用的
        // 从头开始删除过期条目，遇到第一个未过期的即停止
        for (const key of this.cache.keys()) {
            const entry = this.cache.get(key);
            if (entry && (now - entry.lastAccess > this.ttl)) {
                this.cache.delete(key);
            } else {
                break;
            }
        }
    }

    size() { return this.cache.size; }
}

const pageCache = new LRUCache(500, 30 * 60 * 1000);
const tmdbSearchCache = new LRUCache(1000, 24 * 60 * 60 * 1000);
const tmdbDetailCache = new LRUCache(1000, 30 * 60 * 1000);
const runningFetches = new Map();

// ===================== TMDB 全局请求队列与熔断 =====================
const tmdbQueue = {
    activeCount: 0,
    maxConcurrent: 4,
    queue: [],
    enqueue(fn) {
        return new Promise((resolve, reject) => {
            const execute = () => {
                this.activeCount++;
                fn().then(resolve, reject).finally(() => {
                    this.activeCount--;
                    if (this.queue.length > 0) {
                        const next = this.queue.shift();
                        next();
                    }
                });
            };
            if (this.activeCount < this.maxConcurrent) {
                execute();
            } else {
                this.queue.push(execute);
            }
        });
    }
};

const circuitBreaker = {
    failureCount: 0,
    threshold: 5,
    timeout: 5 * 60 * 1000,
    openTime: null,
    isOpen() {
        if (this.openTime === null) return false;
        if (Date.now() - this.openTime >= this.timeout) {
            this.failureCount = 0;
            this.openTime = null;
            return false;
        }
        return true;
    },
    success() {
        this.failureCount = 0;
        this.openTime = null;
    },
    failure() {
        this.failureCount++;
        if (this.failureCount >= this.threshold) {
            this.openTime = Date.now();
            log.warn(`TMDB 连续失败 ${this.threshold} 次，熔断开启 5 分钟`);
        }
    }
};

// ===================== 环境变量读取 TMDB Key =====================
// 注意：TMDB_API_KEY 已移至文件开头第1行
// const TMDB_API_KEY = process.env.TMDB_API_KEY || 'c2d8bcc1ff01b54ee5039d5b70f2b67e';
// const TMDB_IMAGE_BASE = 'https://image.tmdb.org/t/p/w500';

const pickDate = (item) => item.first_air_date || item.release_date || '';
const yearOf = (item) => (pickDate(item) || '').substring(0, 4) || '未知';

const fmtNextAirDate = (dateStr) => {
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const parts = dateStr.split('-').map(Number);
    if (parts.length !== 3) return null;
    const airDate = new Date(parts[0], parts[1] - 1, parts[2]);
    if (airDate < today) return null;
    const key = d => `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
    const todayKey = key(today);
    const airKey = key(airDate);
    if (airKey === todayKey) return '今日更新';
    const tomorrow = new Date(today); tomorrow.setDate(today.getDate() + 1);
    if (airKey === key(tomorrow)) return '明日更新';
    return `${airDate.getMonth() + 1}月${airDate.getDate()}更新`;
};

const getRegionFromTmdb = (item) => {
    const countries = Array.isArray(item.origin_country) ? item.origin_country : [];
    if (countries.includes('CN')) return 'cn';
    if (countries.includes('JP')) return 'jp';
    if (countries.includes('US')) return 'us';
    return 'other';
};

const toVodFromTmdb = (item) => {
    const nextAirDate = item.next_episode_to_air?.air_date;
    const todayStr = (() => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; })();
    let vod_year = nextAirDate ? (fmtNextAirDate(nextAirDate) || yearOf(item)) : yearOf(item);
    if (item.last_episode_to_air?.air_date === todayStr && !vod_year.startsWith('✨')) vod_year = '✨' + vod_year;
    return {
        vod_id: String(item.id),
        vod_name: item.name || '未知',
        vod_pic: item.poster_path ? `${TMDB_IMAGE_BASE}${item.poster_path}` : '',
        vod_year,
        vod_remarks: (item.vote_average ? `${item.vote_average.toFixed(1)}分` : '暂无') + (getTmdbEpisodeText(item) || ''),
        vod_content: item.overview || '暂无简介',
        goSearch: true
    };
};

// ===================== TVmaze 集数外挂（独立队列、熔断、缓存） =====================
const TVMAZE_CACHE_TTL = 10 * 60 * 1000; // 10分钟
const TVMAZE_MAX_CONCURRENT = 3;
const TVMAZE_RATE_LIMIT_MS = 350;        // 每次请求间隔

const tvmazeCache = new LRUCache(500, TVMAZE_CACHE_TTL);
let tvmazeLastRequestTime = 0;

const tvmazeQueue = {
    activeCount: 0,
    maxConcurrent: TVMAZE_MAX_CONCURRENT,
    queue: [],
    enqueue(fn) {
        return new Promise((resolve, reject) => {
            const execute = async () => {
                this.activeCount++;
                const now = Date.now();
                const wait = Math.max(0, TVMAZE_RATE_LIMIT_MS - (now - tvmazeLastRequestTime));
                if (wait > 0) {
                    await new Promise(r => setTimeout(r, wait));
                }
                tvmazeLastRequestTime = Date.now();
                fn().then(resolve, reject).finally(() => {
                    this.activeCount--;
                    if (this.queue.length > 0) {
                        const next = this.queue.shift();
                        next();
                    }
                });
            };
            if (this.activeCount < this.maxConcurrent) {
                execute();
            } else {
                this.queue.push(execute);
            }
        });
    }
};

// TVmaze 独立熔断
const tvmazeBreaker = {
    failureCount: 0,
    threshold: 5,
    timeout: 5 * 60 * 1000,
    openTime: null,
    isOpen() {
        if (this.openTime === null) return false;
        if (Date.now() - this.openTime >= this.timeout) {
            this.failureCount = 0;
            this.openTime = null;
            return false;
        }
        return true;
    },
    success() {
        this.failureCount = 0;
        this.openTime = null;
    },
    failure() {
        this.failureCount++;
        if (this.failureCount >= this.threshold) {
            this.openTime = Date.now();
            log.warn(`TVmaze 连续失败 ${this.threshold} 次，熔断开启 5 分钟`);
        }
    }
};

const tvmazeRequest = async (url) => {
    if (tvmazeBreaker.isOpen()) return null;
    const cacheKey = `req:${url}`;
    const cached = tvmazeCache.get(cacheKey);
    if (cached !== undefined) return cached;

    const doRequest = async () => {
        const data = await requestWithRetry(url, { timeout: 5000, responseType: 'json' });
        if (data) {
            tvmazeCache.set(cacheKey, data);
            tvmazeBreaker.success();
            return data;
        }
        tvmazeBreaker.failure();
        return null;
    };

    try {
        return await tvmazeQueue.enqueue(doRequest);
    } catch (e) {
        tvmazeBreaker.failure();
        return null;
    }
};

const getTVmazeEpisodeText = async (name, year) => {
    if (!name || tvmazeBreaker.isOpen()) return null;
    const cacheKey = `show:${name}_${year || 'no_year'}`;
    const cachedText = tvmazeCache.get(cacheKey);
    if (cachedText !== undefined) return cachedText;

    try {
        // 使用文件开头定义的 TVMAZE_API_BASE
        const searchUrl = `${TVMAZE_API_BASE}/search/shows?q=${encodeURIComponent(name)}`;
        const searchData = await tvmazeRequest(searchUrl);
        if (!searchData || !Array.isArray(searchData) || searchData.length === 0) {
            tvmazeCache.set(cacheKey, null);
            return null;
        }

        let show = searchData[0].show;
        if (year && searchData.length > 1) {
            for (const item of searchData) {
                const premiered = item.show.premiered;
                if (premiered && premiered.startsWith(year)) {
                    show = item.show;
                    break;
                }
            }
        }

        const episodesUrl = `${TVMAZE_API_BASE}/shows/${show.id}/episodes`;
        const episodes = await tvmazeRequest(episodesUrl);
        if (!episodes || !Array.isArray(episodes)) {
            tvmazeCache.set(cacheKey, null);
            return null;
        }

const now = new Date();
        const aired = episodes.filter(ep => {
            if (!ep.airstamp) return false;
            return new Date(ep.airstamp) <= now;
        });
        const unaired = episodes.filter(ep => {
            if (!ep.airstamp) return false;
            return new Date(ep.airstamp) > now;
        });
        const nextAirDate = unaired.length > 0 ? unaired[0].airstamp.split('T')[0] : null;
        tvmazeCache.set(`air:${cacheKey}`, nextAirDate);
        const lastAirDate = aired.length > 0 ? aired[aired.length - 1].airstamp.split('T')[0] : null;
        tvmazeCache.set(`lastAir:${cacheKey}`, lastAirDate);

        if (show.status === 'Ended') {
            const total = aired.length;
            if (total > 0) {
                const text = `(全${total}集)`;
                tvmazeCache.set(cacheKey, text);
                return text;
            }
        } else {
            const airedCount = aired.length;
            if (airedCount > 0) {
                const text = `(更至${airedCount}集)`;
                tvmazeCache.set(cacheKey, text);
                return text;
            }
        }
        tvmazeCache.set(cacheKey, null);
        return null;
    } catch (e) {
        log.warn(`TVmaze 集数获取异常: ${name} - ${e.message}`);
        tvmazeCache.set(cacheKey, null);
        return null;
    }
};

const batchGetTVmazeEpisodeTexts = async (items) => {
    const results = new Map();
    const lastAirMap = new Map();
    const nextAirMap = new Map();
    const promises = items.map(async (item) => {
        const text = await getTVmazeEpisodeText(item.vod_name, item.vod_year);
        if (text) results.set(item.vod_id, text);
        const laKey = `lastAir:show:${item.vod_name}_${item.vod_year || 'no_year'}`;
        const la = tvmazeCache.get(laKey);
        if (la) lastAirMap.set(item.vod_id, la);
        const naKey = `air:show:${item.vod_name}_${item.vod_year || 'no_year'}`;
        const na = tvmazeCache.get(naKey);
        if (na) nextAirMap.set(item.vod_id, na);
    });
    await Promise.allSettled(promises);
    return { episodeTexts: results, lastAirDates: lastAirMap, nextAirDates: nextAirMap };
};

// ===================== 核心 TMDB 请求（带缓存、队列、熔断） =====================
let fetchTmdb = async (endpoint, params = {}) => {
    if (circuitBreaker.isOpen()) {
        return null;
    }

    let cached = null;
    let idMatch = null;
    if (endpoint.match(/^\/tv\/\d+$/)) {
        idMatch = endpoint.match(/^\/tv\/(\d+)$/);
        if (idMatch) {
            cached = tmdbDetailCache.get(idMatch[1]);
            if (cached) return cached;
        }
    }

    const doRequest = async () => {
        const tmdbApis = ['https://api.tmdb.org/3', 'https://api.themoviedb.org/3'];
        let lastError;
        for (const baseUrl of tmdbApis) {
            try {
                const data = await requestWithRetry(`${baseUrl}${endpoint}`, {
                    params: { api_key: TMDB_API_KEY, language: 'zh-CN', ...params },
                    responseType: 'json', timeout: 15000
                });
                if (!data) throw new Error('Empty response');
                if (idMatch) {
                    tmdbDetailCache.set(idMatch[1], data);
                }
                circuitBreaker.success();
                return data;
            } catch (e) {
                lastError = e;
            }
        }
        circuitBreaker.failure();
        throw lastError || new Error('All TMDB APIs failed');
    };

    try {
        return await tmdbQueue.enqueue(doRequest);
    } catch (e) {
        return null;
    }
};

// ===================== TMDB 搜索（带缓存、安全季数去除） =====================
const searchTmdbTV = async (vodName, vodYear) => {
    let cleanName = vodName
        .replace(/[\s\-\—]*第[一二三四五六七八九十\d]+季\s*$/, '')
        .replace(/[（(][^）)]*[）)]/g, '')
        .replace(/\s+/g, ' ')
        .trim();
    if (!cleanName) cleanName = vodName;

    const cacheKey = `${cleanName}_${vodYear || 'no_year'}`;
    const cachedId = tmdbSearchCache.get(cacheKey);
    if (cachedId !== undefined) {
        return cachedId;
    }

    const doSearch = async (query, year, lang) => {
        const params = { query: encodeURIComponent(query), language: lang };
        if (year) params.first_air_date_year = year;
        const data = await fetchTmdb(`/search/tv`, params);
        return data?.results || [];
    };

    let resultId = null;

    let results = await doSearch(cleanName, vodYear, 'zh-CN');
    if (results.length > 0) resultId = results[0].id;

    if (!resultId) {
        const enMatch = cleanName.match(/[（(]([a-zA-Z][^）)]+)[）)]/);
        let enName = enMatch ? enMatch[1] : cleanName.replace(/[^\x00-\x7F]/g, '').trim();
        if (enName && enName.length >= 2) {
            results = await doSearch(enName, vodYear, 'en-US');
            if (results.length > 0) resultId = results[0].id;
        }
    }

    if (!resultId && vodYear) {
        results = await doSearch(cleanName, null, 'zh-CN');
        if (results.length > 0) resultId = results[0].id;
        if (!resultId) {
            const enMatch = cleanName.match(/[（(]([a-zA-Z][^）)]+)[）)]/);
            let enName = enMatch ? enMatch[1] : cleanName.replace(/[^\x00-\x7F]/g, '').trim();
            if (enName) {
                results = await doSearch(enName, null, 'en-US');
                if (results.length > 0) resultId = results[0].id;
            }
        }
    }

    if (!resultId) {
        const mainTitle = cleanName.split(/[\s\-–—·]+/)[0];
        if (mainTitle && mainTitle !== cleanName && mainTitle.length >= 2) {
            results = await doSearch(mainTitle, vodYear, 'zh-CN');
            if (results.length > 0) resultId = results[0].id;
        }
    }

    tmdbSearchCache.set(cacheKey, resultId);
    return resultId;
};

// ===================== 批量获取TMDB详情 =====================
const fetchTmdbDetailsBatch = async (results) => {
    if (!results || results.length === 0) return {};
    const batchSize = 5, detailsMap = {};
    for (let i = 0; i < results.length; i += batchSize) {
        const batch = results.slice(i, i + batchSize);
        const batchResults = await Promise.all(
            batch.map(item => fetchTmdb(`/tv/${item.id}`, {}))
        );
        batchResults.forEach(detail => {
            if (detail?.id) detailsMap[detail.id] = detail;
        });
        if (i + batchSize < results.length) await new Promise(r => setTimeout(r, 250));
    }
    return detailsMap;
};

// ===================== 豆瓣请求降级辅助 =====================
const fetchCategoryLive = async ({ id, page, filters }) => {
    let pg = parseInt(page);
    if (isNaN(pg) || pg < 1) pg = 1;

    let url = "", referer = "", isHTML = false;

    try {
        if (id === 'movie') {
            const count = 20, start = (pg - 1) * count;
            const category = filters.category || '热门', type = filters.type || '全部';
            url = `${DOUBAN_API_BASE}/subject/recent_hot/movie?start=${start}&limit=${count}&category=${encodeURIComponent(category)}&type=${encodeURIComponent(type)}`;
            referer = 'https://movie.douban.com/explore';
        } else if (id === 'tv' || id === 'show') {
            const count = 20, start = (pg - 1) * count;
            const type = filters.type || (id === 'tv' ? 'tv' : 'show');
            url = `${DOUBAN_API_BASE}/subject/recent_hot/tv?start=${start}&limit=${count}&category=${id}&type=${encodeURIComponent(type)}`;
            referer = 'https://movie.douban.com/tv/';
        } else if (id === 'top_250') {
            const count = 25, start = (pg - 1) * count;
            url = `https://movie.douban.com/top250?start=${start}`;
            referer = 'https://movie.douban.com/top250';
            isHTML = true;
        } else {
            return { list: [], page: pg, pagecount: 1, limit: 20, total: 0 };
        }

        const options = { headers: { referer }, timeout: 15000 };
        if (isHTML) options.responseType = 'text';

        const data = await requestWithRetry(url, options);
        if (!data) throw new Error('请求失败');

        if (id === 'top_250') {
            const items = parseTop250HTML(data);
            const pageSize = 25;
            return { list: items.slice(0, pageSize), page: pg, pagecount: Math.ceil(250 / pageSize), limit: pageSize, total: 250 };
        }

        if (!data.items) throw new Error('数据格式错误');

        const items = data.items.map(item => {
            const yearMatch = (item.card_subtitle || "").match(/^(\d{4})/);
            return {
                vod_id: String(item.id || `douban_${item.uri}`).trim(),
                vod_name: item.title || '',
                vod_pic: (item.pic?.large || item.pic?.normal || "").replace(/img\d.doubanio.com/g, 'img1.doubanio.com'),
                vod_year: yearMatch ? yearMatch[1] : "",
                vod_remarks: getScoreText(item, id),
                goSearch: true
            };
        });

        if (id === 'tv' || id === 'show') {
            try {
                const tvmazeResult = await batchGetTVmazeEpisodeTexts(items);
                const tvmazeMap = tvmazeResult.episodeTexts;
                const lastAirDates = tvmazeResult.lastAirDates;
                const nextAirDates = tvmazeResult.nextAirDates;
                const tvHandled = new Set();
                for (const item of items) {
                    const epText = tvmazeMap.get(item.vod_id);
                    if (epText) {
                        item.vod_remarks = `${item.vod_remarks}${epText}`;
                        tvHandled.add(item.vod_id);
                    }
                }
                const todayStr = (() => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; })();
                const remaining = items.filter(it => !tvHandled.has(it.vod_id));
                if (remaining.length > 0) {
                    const searchResults = await Promise.allSettled(
                        remaining.map(item => searchTmdbTV(item.vod_name, item.vod_year).then(tmdb_id => ({ vod_id: item.vod_id, tmdb_id })))
                    );
                    const mappings = searchResults.filter(r => r.status === 'fulfilled' && r.value?.tmdb_id).map(r => r.value);
                    if (mappings.length > 0) {
                        const batchSize = 5, detailsMap = {};
                        for (let i = 0; i < mappings.length; i += batchSize) {
                            const batch = mappings.slice(i, i + batchSize);
                            const details = await Promise.allSettled(
                                batch.map(m => fetchTmdb(`/tv/${m.tmdb_id}`, { language: 'zh-CN' }))
                            );
                            details.forEach((d, idx) => {
                                if (d.status === 'fulfilled' && d.value?.id) detailsMap[batch[idx].vod_id] = d.value;
                            });
                            if (i + batchSize < mappings.length) await new Promise(r => setTimeout(r, 300));
                        }
                        remaining.forEach(item => {
                            const detail = detailsMap[item.vod_id];
                            if (detail) {
                                const epText = getTmdbEpisodeText(detail);
                                if (epText) item.vod_remarks = `${item.vod_remarks}${epText}`;
                                const nextAirDate = detail.next_episode_to_air?.air_date;
                                if (nextAirDate) { const f = fmtNextAirDate(nextAirDate); if (f) item.vod_year = f; }
                                if (detail.last_episode_to_air?.air_date === todayStr && item.vod_year && !item.vod_year.startsWith('✨')) item.vod_year = '✨' + item.vod_year;
                            }
                        });
                    }
                }
                const tvHandledArr = items.filter(it => tvHandled.has(it.vod_id));
                if (tvHandledArr.length > 0) {
                    const searchResults = await Promise.allSettled(
                        tvHandledArr.map(item => searchTmdbTV(item.vod_name, item.vod_year).then(tmdb_id => ({ vod_id: item.vod_id, tmdb_id })))
                    );
                    const mappings = searchResults.filter(r => r.status === 'fulfilled' && r.value?.tmdb_id).map(r => r.value);
                    if (mappings.length > 0) {
                        const batchSize = 5;
                        for (let i = 0; i < mappings.length; i += batchSize) {
                            const batch = mappings.slice(i, i + batchSize);
                            const details = await Promise.allSettled(
                                batch.map(m => fetchTmdb(`/tv/${m.tmdb_id}`, { language: 'zh-CN' }))
                            );
                            details.forEach((d, idx) => {
                                if (d.status === 'fulfilled' && d.value?.next_episode_to_air?.air_date) {
                                    const item = tvHandledArr.find(it => it.vod_id === batch[idx].vod_id);
                                    if (item) {
                                        const f = fmtNextAirDate(d.value.next_episode_to_air.air_date);
                                        if (f) item.vod_year = f;
                                    }
                                }
                            });
                            if (i + batchSize < mappings.length) await new Promise(r => setTimeout(r, 300));
                        }
                    }
                }
                // ✨ 标记：TVmaze 最后播出日期为今天的剧
                for (const item of items) {
                    if (item.vod_year && !item.vod_year.startsWith('✨')) {
                        const lastAir = lastAirDates.get(item.vod_id);
                        if (lastAir === todayStr) {
                            const nextAir = nextAirDates.get(item.vod_id);
                            if (nextAir) {
                                const f = fmtNextAirDate(nextAir);
                                const np = nextAir.split('-');
                                item.vod_year = '✨' + (f === '今日更新' ? '⌛️今日更新' : f);
                            } else {
                                item.vod_year = '✨' + item.vod_year;
                            }
                        }
                    }
                }
                // TMDB 补播出日：仅对 TVmaze 无下集的剧
                const tvHandledArr = items.filter(it => tvHandled.has(it.vod_id) && !nextAirDates.get(it.vod_id));
                if (tvHandledArr.length > 0) {
                    const searchResults = await Promise.allSettled(
                        tvHandledArr.map(item => searchTmdbTV(item.vod_name, item.vod_year).then(tmdb_id => ({ vod_id: item.vod_id, tmdb_id })))
                    );
                    const mappings = searchResults.filter(r => r.status === 'fulfilled' && r.value?.tmdb_id).map(r => r.value);
                    if (mappings.length > 0) {
                        for (let i = 0; i < mappings.length; i += 5) {
                            const batch = mappings.slice(i, i + 5);
                            const details = await Promise.allSettled(
                                batch.map(m => fetchTmdb(`/tv/${m.tmdb_id}`, { language: 'zh-CN' }))
                            );
                            details.forEach((d, idx) => {
                                if (d.status === 'fulfilled' && d.value?.next_episode_to_air?.air_date) {
                                    const item = tvHandledArr.find(it => it.vod_id === batch[idx].vod_id);
                                    if (item) {
                                        const f = fmtNextAirDate(d.value.next_episode_to_air.air_date);
                                        if (f) item.vod_year = f;
                                    }
                                }
                            });
                            if (i + 5 < mappings.length) await new Promise(r => setTimeout(r, 300));
                        }
                    }
                }
                // ✨ 标记：TVmaze 最后播出日期为今天的剧
                for (const item of items) {
            if (item.vod_year && /^\d{4}$/.test(item.vod_year)) {
                const cacheKey = `air:show:${item.vod_name}_${item.vod_year}`;
                const nextAir = tvmazeCache.get(cacheKey);
                if (nextAir) {
                    const f = fmtNextAirDate(nextAir);
                    if (f) item.vod_year = f;
                }
            }
        }

        const seen = new Set();
        const unique = items.filter(it => seen.has(it.vod_id) ? false : (seen.add(it.vod_id), true));
        const limit = 20;
        const list = unique.slice(0, limit);
        const maxSafePage = 50;
        const pagecount = unique.length >= limit ? Math.min(pg + 1, maxSafePage) : pg;
        return { list, page: pg, pagecount, limit, total: unique.length };
    } catch (e) {
        log.error(`fetchCategoryLive 异常: ${e.message}`);
        // 豆瓣请求失败，尝试返回过期缓存（如果存在）
        const cacheKey = getPageCacheKey(id, page, filters);
        const staleCache = pageCache.get(cacheKey);
        if (staleCache) {
            log.info(`豆瓣请求失败，返回过期缓存: ${cacheKey}`);
            return staleCache;
        }
        return { list: [], page: pg, pagecount: 1, limit: 20, total: 0 };
    }
};

const _category = async ({ id, page, filters }) => {
    const cacheKey = getPageCacheKey(id, page, filters);
    const cached = pageCache.get(cacheKey);
    if (cached) return cached;

    if (runningFetches.has(cacheKey)) {
        return runningFetches.get(cacheKey);
    }

    const fetchPromise = fetchCategoryLive({ id, page, filters }).then(liveData => {
        pageCache.set(cacheKey, liveData);
        return liveData;
    });

    const timeoutPromise = Promise.race([
        fetchPromise,
        new Promise((_, reject) => setTimeout(() => reject(new Error('超时')), 15000))
    ]).catch(() => {
        return { list: [], page, pagecount: 1, limit: 20, total: 0 };
    }).finally(() => {
        runningFetches.delete(cacheKey);
    });

    runningFetches.set(cacheKey, timeoutPromise);
    return timeoutPromise;
};

// ===================== 首页推荐 =====================
const fetchHomeList = async () => {
    try {
        const [movieData, tvData] = await Promise.all([
            requestWithRetry(`${DOUBAN_API_BASE}/subject/recent_hot/movie?start=0&limit=10&category=最新&type=全部`, { headers: { referer: "https://movie.douban.com/explore" } }),
            requestWithRetry(`${DOUBAN_API_BASE}/subject/recent_hot/tv?start=0&limit=10&category=tv&type=tv`, { headers: { referer: "https://movie.douban.com/tv/" } })
        ]);

        let combined = [];
        if (movieData?.items) {
            combined = combined.concat(movieData.items.map(item => {
                const yearMatch = (item.card_subtitle || "").match(/^(\d{4})/);
                return {
                    vod_id: String(item.id || `douban_${item.uri}`).trim(),
                    vod_name: item.title || '',
                    vod_pic: (item.pic?.large || item.pic?.normal || "").replace(/img\d.doubanio.com/g, 'img1.doubanio.com'),
                    vod_year: yearMatch ? yearMatch[1] : "",
                    vod_remarks: getScoreText(item, 'movie'),
                    goSearch: true
                };
            }));
        }
        if (tvData?.items) {
            const tvItems = tvData.items.map(item => {
                const yearMatch = (item.card_subtitle || "").match(/^(\d{4})/);
                return {
                    vod_id: String(item.id || `douban_${item.uri}`).trim(),
                    vod_name: item.title || '',
                    vod_pic: (item.pic?.large || item.pic?.normal || "").replace(/img\d.doubanio.com/g, 'img1.doubanio.com'),
                    vod_year: yearMatch ? yearMatch[1] : "",
                    vod_remarks: getScoreText(item, 'tv'),
                    goSearch: true
                };
            });

            try {
                const tvmazeResult = await batchGetTVmazeEpisodeTexts(tvItems);
                const tvmazeMap = tvmazeResult.episodeTexts;
                const lastAirDates = tvmazeResult.lastAirDates;
                const nextAirDates = tvmazeResult.nextAirDates;
                const tvHandled = new Set();
                for (const item of tvItems) {
                    const epText = tvmazeMap.get(item.vod_id);
                    if (epText) {
                        item.vod_remarks = `${item.vod_remarks}${epText}`;
                        tvHandled.add(item.vod_id);
                    }
                }
                const todayStr = (() => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; })();
                const remaining = tvItems.filter(it => !tvHandled.has(it.vod_id));
                if (remaining.length > 0) {
                    const searchResults = await Promise.allSettled(
                        remaining.map(item => searchTmdbTV(item.vod_name, item.vod_year).then(tmdb_id => ({ vod_id: item.vod_id, tmdb_id })))
                    );
                    const mappings = searchResults.filter(r => r.status === 'fulfilled' && r.value?.tmdb_id).map(r => r.value);
                    if (mappings.length > 0) {
                        const detailsMap = {};
                        for (let i = 0; i < mappings.length; i += 5) {
                            const batch = mappings.slice(i, i + 5);
                            const details = await Promise.allSettled(
                                batch.map(m => fetchTmdb(`/tv/${m.tmdb_id}`, { language: 'zh-CN' }))
                            );
                            details.forEach((d, idx) => {
                                if (d.status === 'fulfilled' && d.value?.id) detailsMap[batch[idx].vod_id] = d.value;
                            });
                            if (i + 5 < mappings.length) await new Promise(r => setTimeout(r, 250));
                        }
                        remaining.forEach(item => {
                            const detail = detailsMap[item.vod_id];
                            if (detail) {
                                const epText = getTmdbEpisodeText(detail);
                                if (epText) item.vod_remarks = `${item.vod_remarks}${epText}`;
                                const nextAirDate = detail.next_episode_to_air?.air_date;
                                if (nextAirDate) { const f = fmtNextAirDate(nextAirDate); if (f) item.vod_year = f; }
                                if (detail.last_episode_to_air?.air_date === todayStr && item.vod_year && !item.vod_year.startsWith('✨')) item.vod_year = '✨' + item.vod_year;
                            }
                        });
                    }
                }
                const tvHandledArr = tvItems.filter(it => tvHandled.has(it.vod_id));
                if (tvHandledArr.length > 0) {
                    const searchResults = await Promise.allSettled(
                        tvHandledArr.map(item => searchTmdbTV(item.vod_name, item.vod_year).then(tmdb_id => ({ vod_id: item.vod_id, tmdb_id })))
                    );
                    const mappings = searchResults.filter(r => r.status === 'fulfilled' && r.value?.tmdb_id).map(r => r.value);
                    if (mappings.length > 0) {
                        for (let i = 0; i < mappings.length; i += 5) {
                            const batch = mappings.slice(i, i + 5);
                            const details = await Promise.allSettled(
                                batch.map(m => fetchTmdb(`/tv/${m.tmdb_id}`, { language: 'zh-CN' }))
                            );
                            details.forEach((d, idx) => {
                                if (d.status === 'fulfilled' && d.value?.next_episode_to_air?.air_date) {
                                    const item = tvHandledArr.find(it => it.vod_id === batch[idx].vod_id);
                                    if (item) {
                                        const f = fmtNextAirDate(d.value.next_episode_to_air.air_date);
                                        if (f) item.vod_year = f;
                                    }
                                }
                            });
                            if (i + 5 < mappings.length) await new Promise(r => setTimeout(r, 250));
                        }
                    }
                }
                // 更新日期：TVmaze 下集日期写入 vod_year
                for (const item of tvItems) {
                    const nextAir = nextAirDates.get(item.vod_id);
                    if (nextAir) {
                        const f = fmtNextAirDate(nextAir);
                        if (f) item.vod_year = f;
                    } else if (item.vod_year && /^\d{4}$/.test(item.vod_year)) {
                        const cacheKey = `air:show:${item.vod_name}_${item.vod_year}`;
                        const na = tvmazeCache.get(cacheKey);
                        if (na) {
                            const f = fmtNextAirDate(na);
                            if (f) item.vod_year = f;
                        }
                    }
                }
                // ✨ 标记：TVmaze 最后播出日期为今天的剧
                for (const item of tvItems) {
                    const lastAir = lastAirDates.get(item.vod_id);
                    if (lastAir === todayStr) {
                        const nextAir = nextAirDates.get(item.vod_id);
                        if (nextAir) {
                            const f = fmtNextAirDate(nextAir);
                            item.vod_year = '✨' + (f === '今日更新' ? '⌛️今日更新' : f);
                        } else {
                            item.vod_year = '✨' + (item.vod_year || '');
                        }
                    }
                }
            } catch (e) {
                log.warn(`首页 TVmaze/TMDB 集数失败: ${e.message}`);
            }

            combined = combined.concat(tvItems);
        }
        for (let i = combined.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [combined[i], combined[j]] = [combined[j], combined[i]];
        }
        return combined.slice(0, 20);
    } catch (e) {
        log.warn(`首页推荐失败: ${e.message}`);
        return [];
    }
};

// ===================== TMDB 动漫榜单 =====================
const loadTmdbAnimeRanking = async ({ page, sort_by, year, region }) => {
    const pg = Math.max(1, parseInt(page) || 1);
    const cacheKey = getPageCacheKey('anime_tmdb', pg, { tmdb_sort: sort_by, tmdb_year: year, tmdb_region: region });
    const cached = pageCache.get(cacheKey);
    if (cached) return cached;
    if (runningFetches.has(cacheKey)) return runningFetches.get(cacheKey);

    const query = { page: pg, with_genres: '16', include_adult: false };

    if (sort_by === 'latest_premiere') query.sort_by = 'first_air_date.desc';
    else if (sort_by === 'popular_reputation') { query.sort_by = 'vote_average.desc'; query['vote_count.gte'] = region === 'cn' ? 50 : 200; }
    else if (sort_by === 'hot_following') query.sort_by = 'popularity.desc';
    else { query.sort_by = 'popularity.desc'; query['first_air_date.gte'] = `${new Date().getFullYear() - 2}-01-01`; query['vote_count.gte'] = 100; }

    if (year && year !== 'all') { query['first_air_date.gte'] = `${year}-01-01`; query['first_air_date.lte'] = `${year}-12-31`; }
    if (region === 'cn') query.with_origin_country = 'CN';
    else if (region === 'jp') query.with_origin_country = 'JP';
    else if (region === 'us') query.with_origin_country = 'US';

    const fetchPromise = (async () => {
        try {
            const data = await fetchTmdb('/discover/tv', query);
            const totalPages = data?.total_pages || 0;
            let results = data?.results || [];
            if (region === 'other') results = results.filter(x => getRegionFromTmdb(x) === 'other');
            if (results.length > 0) {
                const detailsMap = await fetchTmdbDetailsBatch(results);
                results = results.map(item => ({ ...item, ...(detailsMap[item.id] || {}) }));
            }
            const list = results.map(toVodFromTmdb);
            // TVmaze 富化（集数 + 更新日期 + ✨ 标记）
            try {
                const tvmazeResult = await batchGetTVmazeEpisodeTexts(list);
                const tvmazeMap = tvmazeResult.episodeTexts;
                const lastAirDates = tvmazeResult.lastAirDates;
                const nextAirDates = tvmazeResult.nextAirDates;
                const todayStr = (() => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; })();
                for (const item of list) {
                    const epText = tvmazeMap.get(item.vod_id);
                    if (epText) {
                        const baseScore = item.vod_remarks.split('(')[0].trim();
                        item.vod_remarks = baseScore ? `${baseScore}${epText}` : item.vod_remarks;
                    }
                }
                for (const item of list) {
                    const nextAir = nextAirDates.get(item.vod_id);
                    if (nextAir) {
                        const f = fmtNextAirDate(nextAir);
                        if (f) item.vod_year = f;
                    }
                }
                for (const item of list) {
                    const lastAir = lastAirDates.get(item.vod_id);
                    if (lastAir === todayStr) {
                        const nextAir = nextAirDates.get(item.vod_id);
                        if (nextAir) {
                            const f = fmtNextAirDate(nextAir);
                            item.vod_year = '✨' + (f === '今日更新' ? '⌛️今日更新' : f);
                        } else {
                            item.vod_year = '✨' + (item.vod_year || '');
                        }
                    }
                }
            } catch (e) {
                log.warn(`动漫 TVmaze 富化失败: ${e.message}`);
            }
            return { list, page: pg, pagecount: totalPages, limit: 20, total: data?.total_results || 0 };
        } catch (e) {
            log.error(`TMDB 动漫失败: ${e.message}`);
            return { list: [], page: pg, pagecount: 1, limit: 20, total: 0 };
        }
    })().then(data => {
        pageCache.set(cacheKey, data);
        return data;
    });

    const timeoutPromise = Promise.race([
        fetchPromise,
        new Promise((_, reject) => setTimeout(() => reject(new Error('超时')), 15000))
    ]).catch(() => {
        return { list: [], page: pg, pagecount: 1, limit: 20, total: 0 };
    }).finally(() => {
        runningFetches.delete(cacheKey);
    });

    runningFetches.set(cacheKey, timeoutPromise);
    return timeoutPromise;
};

// ===================== Top250 HTML 解析增强 =====================
const parseTop250HTML = (html) => {
    const items = [];
    if (!html) return items;
    try {
        const $ = cheerio.load(html);
        const grid = $('.grid_view');
        if (!grid.length) return items;
        grid.find('.item').each((_, el) => {
            const $item = $(el);
            const $pic = $item.find('.pic a');
            const href = $pic.attr('href') || '';
            const idMatch = href.match(/subject\/(\d+)\//);
            if (!idMatch) return;
            const vod_id = idMatch[1];
            const $title = $item.find('.title').first();
            const vod_name = $title.text().trim() || '未知';
            const $img = $pic.find('img');
            let vod_pic = $img.attr('src') || $img.attr('data-original') || '';
            vod_pic = vod_pic.replace(/img\d\.doubanio\.com/g, 'img1.doubanio.com');
            const yearHtml = $item.find('.bd p').first().html() || '';
            const yearMatch = yearHtml.match(/(\d{4})/);
            const rating = $item.find('.rating_num').text().trim() || '暂无评分';
            items.push({
                vod_id, vod_name, vod_pic,
                vod_year: yearMatch ? yearMatch[1] : '',
                vod_remarks: rating ? `${rating}分` : '暂无评分',
                goSearch: true
            });
        });
    } catch (e) {
        log.warn(`Top250 解析异常: ${e.message}`);
    }
    return items;
};

// ===================== 请求入口 =====================
const decodeExt = (ext) => {
    if (!ext) return {};
    try { return JSON.parse(Buffer.from(ext, 'base64').toString('utf-8')); }
    catch { try { return JSON.parse(ext); } catch { return {}; } }
};

const handleT4Request = async (req) => {
    const { t, pg, ext } = req.query;
    const page = parseInt(pg) || 1;

    if (t) {
        const filters = decodeExt(ext);
        let result;
        if (t === 'anime_tmdb') {
            result = await loadTmdbAnimeRanking({ page, sort_by: filters.tmdb_sort || 'hot_following', year: filters.tmdb_year || 'all', region: filters.tmdb_region || 'cn' });
        } else {
            result = await _category({ id: t, page, filters });
        }
        // 预取 N+1 和 N+2（链式：N+1 完成后触发 N+2，只预取 2 页）
        if (result?.list?.length > 0 && result.page < result.pagecount) {
            const pg1 = result.page + 1;
            const pgLimit = pg1 + 1; // 最多预取到 N+2
            const makeKey = (pg) => t === 'anime_tmdb'
                ? getPageCacheKey('anime_tmdb', pg, { tmdb_sort: filters.tmdb_sort || 'hot_following', tmdb_year: filters.tmdb_year || 'all', tmdb_region: filters.tmdb_region || 'cn' })
                : getPageCacheKey(t, pg, filters);
            const fetchPage = (pg) => {
                if (pg > result.pagecount || pg > pgLimit) return;
                if (pageCache.get(makeKey(pg))) {
                    fetchPage(pg + 1);
                    return;
                }
                const p = t === 'anime_tmdb'
                    ? loadTmdbAnimeRanking({ page: pg, sort_by: filters.tmdb_sort || 'hot_following', year: filters.tmdb_year || 'all', region: filters.tmdb_region || 'cn' })
                    : _category({ id: t, page: pg, filters });
                p.then(() => fetchPage(pg + 1)).catch(() => {});
            };
            fetchPage(pg1);
        }
        return result;
    }

    const classList = [
        { type_id: "movie", type_name: "🔥 热门电影" },
        { type_id: "tv", type_name: "📺 热门剧集" },
        { type_id: "anime_tmdb", type_name: "🎯 动漫" },
        { type_id: "show", type_name: "🎭 热门综艺" },
        { type_id: "top_250", type_name: "🏆 电影Top250" }
    ];
    const [banner, defaultList] = await Promise.all([fetchBanner(), fetchHomeList()]);
    return { class: classList, filters: filterConfig, banner, list: defaultList };
};

// ===================== 缓存清理 =====================
let cleanupTimer = null;
const startCleanupTimer = () => {
    if (cleanupTimer) clearInterval(cleanupTimer);
    cleanupTimer = setInterval(() => {
        pageCache.cleanExpired();
        tmdbSearchCache.cleanExpired();
        tmdbDetailCache.cleanExpired();
        tvmazeCache.cleanExpired();
        log.info(`缓存清理完成，页面:${pageCache.size()}, 搜索:${tmdbSearchCache.size()}, 详情:${tmdbDetailCache.size()}, TVmaze:${tvmazeCache.size()}`);
    }, 10 * 60 * 1000);
};

if (!global.__doubanCleanupRegistered) {
    global.__doubanCleanupRegistered = true;
    setImmediate ? setImmediate(startCleanupTimer) : setTimeout(startCleanupTimer, 100);
}

// ===================== 模块导出 =====================
module.exports = async (server, opt) => {
    await init(server);

    const apiPath = "/video/0";
    server.get(apiPath, async (req, reply) => {
        try { return await handleT4Request(req); }
        catch (error) { log.error(`接口异常: ${error.message}`); return { error: "Internal Server Error" }; }
    });

    opt.sites.push({
        key: "0", name: "豆瓣推荐", type: 4, api: apiPath,
        searchable: 0, quickSearch: 0, filterable: 1, indexs: 1
    });

    log.info(`✅ 豆瓣推荐已加载 (TVmaze 优先集数 + TMDB 兜底 + 豆瓣降级缓存)`);
};
