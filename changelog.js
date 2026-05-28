/**
 * CatVodSpider Danmaku 弹幕UI修改记录
 * 每次修改后请在此文件追加记录，方便回档
 * 格式: { date, files: { 文件名: [{ action, location, desc, before, after, detail }] } }
 */

const mods = [
  {
    date: "2026-05-22",
    files: {
      "DanmakuUIHelper.java": [
        {
          action: "修改",
          location: "createResultButton",
          desc: "按钮文字从单行截断改为自动换行",
          before: "setSingleLine(true), setEllipsize(END)",
          after: "setSingleLine(false), setEllipsize(null), setHorizontallyScrolling(false), setBreakStrategy(HIGH_QUALITY)",
          detail: "同时添加 setGravity(CENTER) 文字居中",
        },
        {
          action: "修改",
          location: "createResultButton",
          desc: "补充 isDarkStyle 配色变量定义",
          before: "accentColor/accentDark/normalBg/selectedBg 变量缺失导致编译错误",
          after: "补全 int 变量声明 + isDarkStyle 分支设置 + 按钮初始背景/文字色",
        },
        {
          action: "新增",
          location: "stripCommonParts (工具方法)",
          desc: "字符级去除同组标题公共前缀和后缀",
          detail: "先找最长公共前缀，再对剩余部分找最长公共后缀；若结果为空则返回原标题",
        },
        {
          action: "新增",
          location: "expansion 展开块（按钮布局）",
          desc: "按钮统一宽度",
          before: "按钮自适应宽度（WRAP_CONTENT）",
          after: "colWidth = (availWidth - 16px*columns) / columns，每个按钮定宽 colWidth",
        },
        {
          action: "新增",
          location: "expansion 展开块（按钮布局）",
          desc: "按钮统一高度",
          before: "按钮高度自适应",
          after: "measure 全部按钮取 maxHeight，统一设置 height=maxHeight",
        },
        {
          action: "新增",
          location: "expansion 展开块（行布局）",
          desc: "行式布局 + 水平居中",
          before: "按钮直接添加到 outerWrap（无行结构）",
          after: "outerWrap(VERTICAL, CENTER_HORIZONTAL) → 每行(HORIZONTAL, Gravity.CENTER) → 按钮",
        },
        {
          action: "修改",
          location: "expansion 展开块",
          desc: "按钮文字使用 stripCommonParts 后的精简标题",
          before: "使用 item.getTitleWithEp() 完整标题",
          after: "展开时先用 stripCommonParts 处理 fullTitles，再 setText(stripped[i])",
        },
      ],
      "DanmakuScanner.java": [
        {
          action: "修改",
          location: "isLikelyEpisodeNumber",
          desc: "集数上限从 99 扩大到 999",
          before: "numValue > 99 排除",
          after: "numValue > 999 排除",
          detail: "同时在 isPureEpisodeNumber 也同步修改，支持 100-999 集长篇",
        },
        {
          action: "新增",
          location: "checkForSpecialPrefix（新方法）",
          desc: "提取集数时保留特殊前缀",
          detail: "检查数字前是否有 彩蛋/预告/采访/特典/花絮/幕后，命中返回 keyword+number；在 candidate 和 possibleEpisodes 返回路径都调用",
        },
      ],
    },
  },
  {
    date: "2026-05-22",
    files: {
      "DanmakuUIHelper.java": [
        {
          action: "修改",
          location: "resultContainer",
          desc: "resultContainer 设置 CENTER_HORIZONTAL",
          before: "未设 gravity，单集按钮左对齐",
          after: "setGravity(Gravity.CENTER_HORIZONTAL)，单集按钮居中显示",
          detail: "模板1/4 选集块一行只有一个时居中",
        },
      ],
    },
  },
  {
    date: "2026-05-22",
    files: {
      "DanmakuUIHelper.java": [
        {
          action: "修改",
          location: "按钮文字",
          desc: "标题首尾空格从4改为2",
          before: 'setText("    " + title + "    ")',
          after: 'setText("  " + title + "  ")',
        },
      ],
    },
  },
];

console.log(JSON.stringify(mods, null, 2));
