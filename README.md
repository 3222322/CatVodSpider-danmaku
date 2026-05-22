# 🎬 CatVodSpider

> 基于CatVod的弹幕增强插件，支持多种视频平台的弹幕功能集成

## 🚀 快速开始

### 📦 不夜项目部署

1. **下载插件文件**
   - 从项目 `jar` 文件夹中下载 `custom_spider.jar`
   - 替换 `/vod/dist` 目录中的同名文件

2. **配置弹幕站点**
   在 `config.json` 中添加以下配置：

   ```json
   "danmu": {
       "name": "LEO| 弹幕",
       "key": "leo 弹幕",
       "type": 3,
       "api": "csp_DanmakuSpider",
       "searchable": 1,
       "ext": {
           "apiUrl": "LogVar弹幕API端点",
           "autoPushEnabled": true,
           "danmakuStyle": "模板一",
           "lpAlpha": 0.9
       }
   }
   ```

### 🔧 其他项目部署

1. **获取插件文件**
   - 下载 `jar` 文件夹中的 `danmu.jar`
   - 将文件存放到接口可访问的位置

2. **站点配置示例**

   ```json
   {
       "name": "LEO| 弹幕",
       "key": "leo 弹幕",
       "type": 3,
       "api": "csp_DanmakuSpider",
       "searchable": 1,
       "jar": "https://gh-proxy.org/https://github.com/Silent1566/CatVodSpider/raw/refs/heads/main/jar/danmu.jar",
       "ext": {
           "apiUrl": "LogVar弹幕API端点",
           "autoPushEnabled": true,
           "danmakuStyle": "模板一",
           "lpAlpha": 0.9
       }
   }
   ```

## ⚙️ 参数配置说明

| 参数名 | 类型 | 默认值 | 说明                   |
|--------|------|--------|----------------------|
| `apiUrl` | String | - | 弹幕接口地址，多个地址用逗号分隔     |
| `autoPushEnabled` | Boolean | `false` | 是否开启自动推送弹幕       |
| `danmakuStyle` | String | `模板一` | 弹幕样式，可选值：模板一、模板二、模板三 |
| `lpAlpha` | Float | `0.9` | 弹幕透明度，取值范围 0.1-1     |
| `lpWidth` | Float | `0.9` | 弹幕搜索框宽度比例，取值范围 0.1-1 |
| `lpHeight` | Float | `0.85` | 弹幕搜索框高度比例，取值范围 0.1-1 |

## 🛠️ 操作指南
- 在弹幕站点中调整配置
- 在播放界面短按Leo弹幕按钮手动搜索弹幕
- 在播放界面长按Leo弹幕按钮打开弹幕面板，内含更多功能

## 📚 技术参考

### 🏗️ 基础框架
- [CatVod](https://github.com/CatVodTVOfficial/CatVodTVSpider) - 核心爬虫框架

### 💡 功能模块
- **弹幕系统**：基于 [@leiatai](https://github.com/leiatai) 分享的源码开发
  - 感谢 [LogVar](https://github.com/huangxd-/danmu_api) 提供的弹幕API 支持
- **Go代理**：集成不夜分享的代理功能
  - [不夜发布页](https://github.com/vodspider/release)
  - [CatSpider仓库](https://github.com/vodspider/catspider)

## 📝 使用说明

1. 确保网络环境可访问配置的API地址
2. 根据实际需求调整弹幕样式和透明度参数
3. 启用自动推送功能可实时同步弹幕数据
4. 支持多地址配置，提高服务可用性

---

<p align="center">Made with ❤️ for better video experience</p>