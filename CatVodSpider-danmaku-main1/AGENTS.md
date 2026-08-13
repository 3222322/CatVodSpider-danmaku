# CatVodSpider — Agent Guide

## Build

```batch
build.bat              # 1) gradlew assembleRelease → 2) jar\genJar.bat
```

Build produces two dex JARs via `jar/genJar.bat` and `jar/danmu.bat`:
- `jar/custom_spider.jar` — with `assets/` bundled (for 不夜 project)
- `jar/danmu.jar` — no assets

Both work by: apktool d → extract spider/js/net/slf4j smali → apktool b → rename to JAR.

APK is only an intermediate; the final deliverable is the dex JAR.
No custom `jarRelease` Gradle task exists — use `build.bat` (or `gradlew assembleRelease` then `jar\genJar.bat`).

## Project structure

```
app/src/main/java/com/github/catvod/
  spider/DanmakuSpider.java   — main entrypoint for CatVod framework
  spider/DanmakuUIHelper.java — all dialog/UI logic (2890+ lines)
  spider/DanmakuScanner.java  — scraping/parsing logic
  spider/LeoDanmakuService.java — API service layer
  spider/DanmakuManager.java  — state management
  spider/DanmakuConfig.java / DanmakuConfigManager.java — config persistence
  net/                        — HTTP/networking wrappers
  js/                         — JS evaluation utilities
```

## Templates (TV UI styles)

4 templates, set via `danmakuStyle` ext param:
| Template | Key constant(s) | UI style |
|----------|----------------|----------|
| 模板一   | PRIMARY_COLOR  | Light blue default |
| 模板二   | ACCENT_T2 (#FF9F0A) | Warm orange |
| 模板三   | ACCENT_T3 (#35C958) | Emerald green |
| 模板四   | TV_* constants (#0B1020 bg, #008DFF accent) | OLED dark, glassmorphism, responsive grid |

Template 4 == `isTVStyle` == TV mode with elevated cards, focus glow, glass drawables.

## Deployment

- catvod ext config needs `"type": 3, "api": "csp_DanmakuSpider"`
- JAR must be uploaded to accessible URL or replaced in `/vod/dist/`
- `custom_spider.jar` and `danmu.jar` follow the same flow

## Key conventions

- All UI is built programmatically (no XML layouts) in `DanmakuUIHelper.java`
- Color constants use 0xAARRGGBB hex format
- Tab accent color for templates 2/3/4 uses `getTemplateAccent()` helper
- TV focus system uses `applyTVFocusGlow()` with scale + elevation + gradient background
- No testing framework, no linter config, no typecheck step
