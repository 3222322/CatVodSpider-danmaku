# CatVodSpider Danmaku 项目

## 项目路径
```
D:\op\CatVodSpider-source-20260521-1741
```

## Git
```
仓库: https://github.com/3222322/CatVodSpider-danmaku（私有）
Token: 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
Git 路径: "C:\Program Files\Git\bin\git.exe"
```

## 编译命令
```bash
cd D:\op\CatVodSpider-source-20260521-1741
.\gradlew.bat assembleRelease --no-daemon
.\jar\genJar.bat
```

## 修改记录
```
D:\op\changelog.js
```

## git 常用命令
```bash
# 查看修改
git diff
# 查看历史
git log --oneline
# 保存修改
git add -A && git commit -m "说明"
# 推送到远程
git push
# 回档到某版本
git log  # 复制 hash
git checkout <hash> -- app/...
```

## 关键文件
- `DanmakuUIHelper.java` - 弹幕UI布局/按钮/展开逻辑
- `DanmakuScanner.java` - 集数提取/特殊前缀
- `DanmakuItem.java` - 数据实体
- `jar/custom_spider.jar` - 编译输出
- `.gitignore` - 排除 build/修改记录.md/changelog.js
