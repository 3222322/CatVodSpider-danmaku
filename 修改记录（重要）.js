/**
 * CatVodSpider 重要修改记录
 */

const mods = [
  {
    date: "2026-05-22",
    desc: "修复搜索缓存级联bug",
    detail: "缓存键从 episodeNames[0] 改为 originalTitle，避免缓存键被之前缓存的值污染导致脏数据永远清不掉",
    files: ["EpisodeInfo.java", "DanmakuScanner.java", "DanmakuUIHelper.java"],
  },
  {
    date: "2026-05-22",
    desc: "自动搜索回退机制",
    detail: "带 &episode= 参数搜不到结果时，自动去掉该参数重试。解决 E60.xxx 等文件中 E 被误识别为集数导致搜不到的问题",
    files: ["LeoDanmakuService.java"],
  },
];

console.log(JSON.stringify(mods, null, 2));
