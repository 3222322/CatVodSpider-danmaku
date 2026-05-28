# AGENTS.md

## 项目概述

单文件 Node.js 后端（`202605141933.js`），作为 TVBox/T4 数据源聚合代理，整合豆瓣、TMDB、TVmaze、腾讯视频四个外部 API。

## 启动/依赖

```bash
npm install axios cheerio
node 202605141933.js  # 需要宿主框架（Fastify 风格 server 对象）
```

依赖：`axios`、`cheerio`。无 `package.json`，直接在宿主应用中 `require` 加载。

## 模块导出格式

```js
module.exports = async (server, opt) => {
    server.get('/video/0', handler);  // 注册 GET 接口
    opt.sites.push({ key: "0", name: "豆瓣推荐", type: 4, ... });
};
```

## 架构要点

- **数据富化顺序**：TVmaze（优先）→ TMDB（兜底）→ 豆瓣评分（基础）
- **缓存机制**：4 个独立 LRU 缓存，**滑动续命**（`get()` 命中刷新 TTL，持续使用不超时）
  - `pageCache`：分页数据，TTL 30 分钟，上限 500 条（豆瓣降级兜底）
  - `tmdbSearchCache`：TMDB 搜索，TTL 24 小时，上限 1000 条
  - `tmdbDetailCache`：TMDB 详情，TTL 30 分钟，上限 1000 条
  - `tvmazeCache`：TVmaze 集数/日期，TTL 10 分钟，上限 500 条
- **预取**：`handleT4Request` 中当前页返回后自动链式拉取 **N+1 和 N+2**（所有分类），写入 `pageCache`，实现逐页滑动秒开
- **限流**：TMDB 最大 4 并发队列；TVmaze 最大 3 并发 + 350ms 间隔
- **熔断**：TMDB / TVmaze 各独立熔断器，连续 5 次失败断开 5 分钟
- **重试**：指数退避，最多 2 次重试
- **超时**：AbortController，统一 15 秒兜底
- **防重复请求**：`runningFetches` Map 合并同一 key 的并发请求

## 筛选器分类

- `movie`：分类（热门/最新/豆瓣高分/冷门佳片）+ 地区（华语/欧美/韩国/日本）
- `tv`：国产剧/欧美剧/日剧/韩剧/动漫/纪录片
- `show`：国内/国外
- `anime_tmdb`：年份 + 地区（中/日/美/其他）+ 榜单（全球趋势/高热追更/最新首播/热门口碑）
- `top_250`：无筛选

## 环境变量

- `TMDB_API_KEY`：TMDB API 密钥（有硬编码 fallback）

## 注意事项

- 图片源：豆瓣走 `img1.doubanio.com`（国内快）；TMDB 走 `image.tmdb.org`（国内直连慢）
- 修改缓存 TTL 时注意 `pageCache` 保持 30 分钟不变（豆瓣降级兜底用）
- 不要在 `fetchCategoryLive` 内部加同步阻塞操作（拖慢翻页响应）
- 新增分类的预取需要在 `handleT4Request` 中补充对应的 `getPageCacheKey` 构造