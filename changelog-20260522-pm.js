/**
 * CatVodSpider 修改记录 (2026-05-22 下午)
 * 从模板1/4居中对齐开始，到自动搜索回退修复
 */

const mods = [
  {
    date: "2026-05-22 ~14:00",
    files: {
      "DanmakuUIHelper.java": [
        {
          action: "修改",
          location: "expansion 行布局 (line 1911)",
          desc: "每行按钮水平居中",
          before: "currentRow 未设 gravity，子项左对齐",
          after: "currentRow.setGravity(Gravity.CENTER)",
          detail: "模板4展开时每行 HORIZONTAL LinearLayout 添加居中，最后一行不满时居中显示",
        },
        {
          action: "修改",
          location: "resultContainer (line 1174)",
          desc: "模板1单集按钮居中",
          before: "未设 gravity，单集按钮左对齐",
          after: "resultContainer.setGravity(Gravity.CENTER_HORIZONTAL)",
          detail: "模板1(单集)按钮居中，模板4的 outerWrap 也已居中",
        },
      ],
    },
  },
  {
    date: "2026-05-22 ~14:30",
    files: {
      "DanmakuUIHelper.java": [
        {
          action: "修改",
          location: "createResultButton & expansion",
          desc: "按钮标题首尾加2空格间距",
          before: "setText(item.getTitleWithEp()) / setText(stripped[i])",
          after: 'setText("  " + title + "  ")',
          detail: "先加了4空格，后来改2空格",
        },
      ],
    },
  },
  {
    date: "2026-05-22 ~15:00",
    files: {
      "EpisodeInfo.java": [
        {
          action: "新增",
          location: "新增字段 originalTitle (line 12, 15-20)",
          desc: "存储提取后的原标题(extractTitle2结果)，用于搜索缓存键",
          before: "无 originalTitle 字段",
          after: "新增 String originalTitle + getter/setter",
        },
      ],
      "DanmakuScanner.java": [
        {
          action: "修改",
          location: "getEpisodeInfo (line 235)",
          desc: "保存 originalTitle",
          before: "未设置 originalTitle",
          after: 'episodeInfo.setOriginalTitle(extractTitle2)',
        },
      ],
      "DanmakuUIHelper.java": [
        {
          action: "修改",
          location: "搜索缓存保存 (line 1216)",
          desc: "缓存键改为 originalTitle，避免级联脏数据",
          before: "cacheKey = episodeInfo.getEpisodeNames().get(0) (可能已是缓存值)",
          after: "cacheKey = episodeInfo.getOriginalTitle() (始终是原标题)",
          detail: "修复了 凡人修仙传→长安24计 这类缓存键错位问题",
        },
        {
          action: "修改",
          location: "搜索框初始关键词 (line 1072)",
          desc: "优先用 originalTitle 读取缓存",
          before: "initialKeyword = episodeNames.get(0)",
          after: "优先 originalTitle，回退到 episodeNames.get(0)",
          detail: "确保搜索框始终基于原标题读取缓存，不受 episodeNames 顺序影响",
        },
      ],
    },
  },
  {
    date: "2026-05-22 ~15:30",
    files: {
      "LeoDanmakuService.java": [
        {
          action: "修改",
          location: "autoSearch (line 298-304)",
          desc: "带集数搜不到时自动回退到不带集数搜索",
          before: "searchDanmaku(episodeInfo, activity, true) 一次，为空直接返回失败",
          after: "先搜带集数，为空且 episodeNum 不为空时，回退搜不带集数；回退模式下跳过集数过滤，只靠标题相似度匹配",
          detail: "修复 E60.No.Other.Love 这类文件中E60被误识别为集数，导致API返回空的问题",
        },
      ],
    },
  },
];

console.log(JSON.stringify(mods, null, 2));
