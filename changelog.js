/**
 * CatVodSpider Danmaku 弹幕UI修改记录
 * 日期: 2026-05-22
 * 文件: DanmakuUIHelper.java, DanmakuScanner.java
 */

const changes = {
  version: "20260522",
  files: {
    DanmakuUIHelper: {
      path: "app/src/main/java/com/github/catvod/spider/DanmakuUIHelper.java",
      modifications: [
        {
          location: "createResultButton (~line 2036)",
          desc: "按钮文字换行支持",
          detail: [
            "setSingleLine(false), setEllipsize(null), setHorizontallyScrolling(false)",
            "BREAK_STRATEGY_HIGH_QUALITY (API 23+) 改善CJK换行",
            "Gravity.CENTER 文字居中",
          ],
        },
        {
          location: "createResultButton (~line 2050)",
          desc: "补充配色变量定义",
          detail: [
            "添加 accentColor, accentDark, normalBg, selectedBg 定义",
            "isDarkStyle 分支设置深浅色配色",
            "按钮初始背景/文字颜色设置",
          ],
        },
        {
          location: "expansion 展开块 (~line 1868)",
          desc: "stripCommonParts 去公共前后缀",
          detail: [
            "展开时对同组所有 fullTitles 做 stripCommonParts",
            "字符级去除公共前缀+后缀，只保留差异部分",
            "单集组不处理",
          ],
        },
        {
          location: "expansion (~line 1882-1897)",
          desc: "按钮统一宽度和高度",
          detail: [
            "colWidth = (availWidth - marginTotal * columns) / columns",
            "measure 所有按钮(EXACTLY width, UNSPECIFIED height) 取 maxHeight",
            "统一设置每个按钮 width=colWidth, height=maxHeight",
          ],
        },
        {
          location: "expansion (~line 1898-1915)",
          desc: "行布局 + 水平居中",
          detail: [
            "outerWrap: VERTICAL LinearLayout, CENTER_HORIZONTAL",
            "每行: HORIZONTAL LinearLayout, Gravity.CENTER",
            "按钮按 columns 分到各行",
          ],
        },
      ],
    },
    DanmakuScanner: {
      path: "app/src/main/java/com/github/catvod/spider/DanmakuScanner.java",
      modifications: [
        {
          location: "isLikelyEpisodeNumber (~line 916)",
          desc: "集数上限从 99 改为 999",
          detail: [
            "numValue > 999 才排除（原 > 99）",
            "支持 100-999 集的长篇剧集",
          ],
        },
        {
          location: "isPureEpisodeNumber (~line 948)",
          desc: "严格检查也支持到 999",
          detail: [
            "numValue > 999 排除（原 > 99）",
            "注释更新为 1-99 常规范围 + 到 999",
          ],
        },
        {
          location: "新增 checkForSpecialPrefix (~line 1005)",
          desc: "保留特殊前缀",
          detail: [
            "检查数字前是否有 彩蛋/预告/采访/特典/花絮/幕后",
            "命中则返回 keyword+number，如 '彩蛋3'",
            "在 extractEpisodeNum 的 candidate 和 possibleEpisodes 路径都调用",
          ],
        },
      ],
    },
    DanmakuItem: {
      path: "app/src/main/java/com/github/catvod/spider/entity/DanmakuItem.java",
      modifications: [],
    },
  },
};

console.log(JSON.stringify(changes, null, 2));
