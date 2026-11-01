package com.github.catvod.spider;

import android.app.Activity;
import com.github.catvod.spider.entity.DanmakuItem;
import com.google.gson.Gson;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class WebServer extends NanoHTTPD {

    // 固定局域网配置 token，避免应用重启后旧二维码立即失效。
    private static final String SETTINGS_TOKEN = "l7n9c2v4m6q8p1x3";

    public WebServer(int port) throws IOException {
        super(port);
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    public String getAutoSettingsUrl() {
        return getSettingsBaseUrl() + "/auto-settings";
    }

    public String getApiSettingsUrl() {
        return getSettingsBaseUrl() + "/api-settings?token=" + SETTINGS_TOKEN;
    }

    public String getTmdbSettingsUrl() {
        return getSettingsBaseUrl() + "/tmdb-settings?token=" + SETTINGS_TOKEN;
    }

    private String getSettingsBaseUrl() {
        int port = getListeningPort();
        if (port < 1) return "";
        return "http://" + NetworkUtils.getLocalIpAddress() + ":" + port;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri.equals("/")) {
            return newFixedLengthResponse(getSettingsChooserHtml());
        } else if (uri.equals("/remote-search")) {
            return newFixedLengthResponse(getHtml());
        } else if (uri.equals("/auto-settings")) {
            return newFixedLengthResponse(getSettingsChooserHtml());
        } else if (uri.equals("/api-settings")) {
            return newFixedLengthResponse(getApiSettingsHtml(""));
        } else if (uri.equals("/tmdb-settings")) {
            return newFixedLengthResponse(getTmdbSettingsHtml(""));
        } else if (uri.equals("/api-settings/save")) {
            return handleDanmakuApiSave(session);
        } else if (uri.equals("/tmdb-settings/save")) {
            return handleAutoSettingsSave(session);
        } else if (uri.equals("/auto-settings/api")) {
            return handleDanmakuApiSave(session);
        } else if (uri.equals("/auto-settings/save")) {
            return handleAutoSettingsSave(session);
        } else if (uri.equals("/search")) {
            Map<String, String> params = session.getParms();
            String keyword = params.get("keyword");
            Activity activity = Utils.getTopActivity();
            // 创建 EpisodeInfo 对象用于远程搜索
            EpisodeInfo episodeInfo = new EpisodeInfo();
            java.util.List<String> names = new java.util.ArrayList<>();
            names.add(keyword);
            episodeInfo.setEpisodeNames(names);
            List<DanmakuItem> results = LeoDanmakuService.manualSearch(episodeInfo, activity);
            return newFixedLengthResponse(new Gson().toJson(results));
        } else if (uri.equals("/select")) {
            Map<String, String> params = session.getParms();
            String epIdStr = params.get("epId");
            if (epIdStr != null) {
                try {
                    int epId = Integer.parseInt(epIdStr);
                    DanmakuItem item = DanmakuManager.lastDanmakuItemMap.get(epId);
                    if (item != null) {
                        Activity activity = Utils.getTopActivity();
                        LeoDanmakuService.pushDanmakuDirect(item, activity, false);
                        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK");
                    }
                } catch (NumberFormatException e) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid epId format.");
                }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Danmaku not found with given epId.");
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
    }

    private Response handleDanmakuApiSave(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, String> params = session.getParms();
            if (!SETTINGS_TOKEN.equals(params.get("token"))) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_HTML,
                        renderMessagePage("禁止访问", "配置链接已失效，请重新扫码。", false));
            }

            String raw = params.get("apiUrls");
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            if (raw != null) {
                for (String line : raw.split("[\\r\\n,]+")) {
                    String value = line.trim();
                    if (value.startsWith("http://") || value.startsWith("https://")) {
                        urls.add(value);
                    }
                }
            }
            if (urls.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_HTML,
                        renderMessagePage("API 地址无效",
                                "请至少填写一个以 http:// 或 https:// 开头的地址。", false));
            }

            Activity activity = Utils.getTopActivity();
            if (activity == null) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_HTML, renderMessagePage("保存失败", "当前应用上下文不可用。", false));
            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            config.setApiUrls(urls);
            DanmakuConfigManager.saveConfig(activity, config);
            DanmakuSpider.clearCache(activity);
            DanmakuSpider.log("弹幕搜索 API 已通过扫码更新，共 " + urls.size() + " 个地址");
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML,
                    renderMessagePage("保存成功", "弹幕搜索 API 已保存，缓存已清空。", true));
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_HTML,
                    renderMessagePage("保存异常", e.getMessage(), false));
        }
    }

    private Response handleAutoSettingsSave(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, String> params = session.getParms();
            if (!SETTINGS_TOKEN.equals(params.get("token"))) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_HTML,
                        renderMessagePage("禁止访问", "配置链接已失效，请重新扫码。", false));
            }

            String mode = params.get("mode");
            String key = params.get("key");
            if ("default".equals(mode)) key = DanmakuConfig.DEFAULT_TMDB_API_KEY;
            if (!AutoSkipService.isValidKey(key)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_HTML,
                        renderMessagePage("Key 格式错误",
                                "请输入 32 位 TMDB v3 API Key，或以 eyJ 开头的 v4 Token。", false));
            }

            String testResult = AutoSkipService.testApiKey(key);
            if (!testResult.contains("成功")) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_HTML,
                        renderMessagePage("TMDB 验证失败", testResult, false));
            }

            Activity activity = Utils.getTopActivity();
            if (activity == null) return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    MIME_HTML, renderMessagePage("保存失败", "当前应用上下文不可用。", false));

            DanmakuConfig config = DanmakuConfigManager.getConfig(activity);
            config.setTmdbApiKey(key);
            config.setAutoSkipEnabled(true);
            DanmakuConfigManager.saveConfig(activity, config);
            AutoSkipService.clearCache(activity);
            DanmakuSpider.log("TMDB Key 已通过扫码更新: "
                    + AutoSkipService.maskKey(key)
                    + " (" + AutoSkipService.getKeyTypeLabel(key) + ")");
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML,
                    renderMessagePage("保存成功",
                            "TMDB Key 已验证并保存，Auto 已开启。电视端无需重启。", true));
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_HTML,
                    renderMessagePage("保存异常", e.getMessage(), false));
        }
    }

    private String getSettingsChooserHtml() {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>猫影视设置</title><style>"
                + "*{box-sizing:border-box}body{margin:0;background:#101522;color:#eef6ff;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:22px}"
                + ".card{max-width:620px;margin:auto;background:#1a2234;border:1px solid #34425e;"
                + "border-radius:12px;padding:22px}a{display:block;text-decoration:none;margin-top:14px;"
                + "padding:16px;border-radius:8px;background:#e67c00;color:#fff;font-weight:700;"
                + "text-align:center}.blue{background:#2f6fed}p{color:#9fb0cc}</style>"
                + "</head><body><div class='card'><h1>猫影视设置</h1>"
                + "<p>请选择要修改的项目。</p><a href='/api-settings'>弹幕搜索 API</a>"
                + "<a class='blue' href='/tmdb-settings'>TMDB Key</a></div></body></html>";
    }

    private String getApiSettingsHtml(String message) {
        Activity activity = Utils.getTopActivity();
        DanmakuConfig config = activity == null
                ? new DanmakuConfig() : DanmakuConfigManager.getConfig(activity);
        StringBuilder apiText = new StringBuilder();
        for (String apiUrl : config.getApiUrls()) {
            if (apiText.length() > 0) apiText.append('\n');
            apiText.append(apiUrl);
        }
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>弹幕搜索 API</title><style>"
                + "*{box-sizing:border-box}body{margin:0;background:#101522;color:#eef6ff;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:22px}"
                + ".card{max-width:620px;margin:auto;background:#1a2234;border:1px solid #34425e;"
                + "border-radius:12px;padding:22px}h1{font-size:22px;margin:0 0 8px}"
                + ".muted{color:#9fb0cc;font-size:14px;line-height:1.6}"
                + "label{display:block;margin:20px 0 8px;font-weight:600}"
                + "textarea{width:100%;padding:13px;border-radius:8px;border:1px solid #4a5b79;"
                + "background:#0d1320;color:#fff;font-size:15px}button{width:100%;margin-top:14px;"
                + "padding:13px;border:0;border-radius:8px;background:#e67c00;color:#fff;"
                + "font-size:16px;font-weight:700}.msg{color:#ffcf70}.card a{color:#ffb84d}"
                + "</style></head><body><div class='card'><h1>弹幕搜索 API</h1>"
                + "<div class='muted'>每行一个地址，保存后会清空弹幕缓存。</div>"
                + (message == null || message.isEmpty() ? "" : "<div class='msg'>"
                + escapeHtml(message) + "</div>")
                + "<form method='post' action='/api-settings/save?token=" + SETTINGS_TOKEN + "'>"
                + "<input type='hidden' name='token' value='" + SETTINGS_TOKEN + "'>"
                + "<label for='apiUrls'>API 地址</label>"
                + "<textarea id='apiUrls' name='apiUrls' rows='5' spellcheck='false'>"
                + escapeHtml(apiText.toString()) + "</textarea>"
                + "<button type='submit'>保存弹幕 API</button></form>"
                + "<p><a href='/auto-settings'>返回设置目录</a></p></div></body></html>";
    }

    private String getTmdbSettingsHtml(String message) {
        Activity activity = Utils.getTopActivity();
        DanmakuConfig config = activity == null
                ? new DanmakuConfig() : DanmakuConfigManager.getConfig(activity);
        String key = config.getTmdbApiKey();
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>TMDB Key</title><style>"
                + "*{box-sizing:border-box}body{margin:0;background:#101522;color:#eef6ff;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:22px}"
                + ".card{max-width:620px;margin:auto;background:#1a2234;border:1px solid #34425e;"
                + "border-radius:12px;padding:22px}h1{font-size:22px;margin:0 0 8px}"
                + ".muted{color:#9fb0cc;font-size:14px;line-height:1.6}.ok{color:#35d99b}"
                + "label{display:block;margin:20px 0 8px;font-weight:600}"
                + "input{width:100%;padding:13px;border-radius:8px;border:1px solid #4a5b79;"
                + "background:#0d1320;color:#fff;font-size:15px}button{width:100%;margin-top:14px;"
                + "padding:13px;border:0;border-radius:8px;background:#e67c00;color:#fff;"
                + "font-size:16px;font-weight:700}.secondary{background:#40506b}"
                + ".note{margin-top:16px;padding:12px;background:#0d1320;border-radius:8px;"
                + "font-size:13px;color:#9fb0cc;line-height:1.6}.msg{color:#ffcf70}"
                + ".card a{color:#ffb84d}</style></head><body><div class='card'><h1>TMDB Key</h1>"
                + "<div class='muted'>当前：<span class='ok'>" + escapeHtml(AutoSkipService.maskKey(key))
                + " · " + escapeHtml(AutoSkipService.getKeyTypeLabel(key)) + "</span></div>"
                + (message == null || message.isEmpty() ? "" : "<div class='msg'>"
                + escapeHtml(message) + "</div>")
                + "<form method='post' action='/tmdb-settings/save?token=" + SETTINGS_TOKEN + "'>"
                + "<input type='hidden' name='token' value='" + SETTINGS_TOKEN + "'>"
                + "<label for='key'>更换 TMDB Key</label>"
                + "<input id='key' name='key' autocomplete='off' spellcheck='false' "
                + "placeholder='32 位 v3 Key，或 eyJ 开头的 v4 Token'>"
                + "<button type='submit'>测试并保存</button></form>"
                + "<form method='post' action='/tmdb-settings/save?token=" + SETTINGS_TOKEN + "'>"
                + "<input type='hidden' name='token' value='" + SETTINGS_TOKEN + "'>"
                + "<input type='hidden' name='mode' value='default'>"
                + "<button class='secondary' type='submit'>恢复内置默认 Key</button></form>"
                + "<div class='note'>Key 只保存在电视本机配置中。</div>"
                + "<p><a href='/auto-settings'>返回设置目录</a></p></div></body></html>";
    }

    private String renderMessagePage(String title, String message, boolean success) {
        String color = success ? "#35d99b" : "#ff8d8d";
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;background:#101522;color:#eef6ff;padding:24px;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}"
                + ".card{max-width:560px;margin:auto;background:#1a2234;border:1px solid #34425e;"
                + "border-radius:12px;padding:24px}h1{color:" + color + ";font-size:22px}"
                + "p{color:#b9c6da;line-height:1.7}a{color:#ffb84d}</style></head><body>"
                + "<div class='card'><h1>" + escapeHtml(title) + "</h1><p>"
                + escapeHtml(message == null ? "" : message) + "</p>"
                + "<a href='/auto-settings'>返回设置目录</a></div></body></html>";
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String getHtml() {
        return "<!DOCTYPE html><html><head><title>弹幕搜索</title><meta name='viewport' content='width=device-width, initial-scale=1.0'><style>" +
                "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif; background-color: #f0f2f5; margin: 0; padding: 10px; }" +
                "h1 { color: #333; text-align: center; font-size: 1.2em; margin: 5px 0 10px 0; } " +
                ".container { max-width: 800px; margin: 0 auto; background-color: #fff; padding: 10px; border-radius: 12px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); } " +
                ".search-box { display: flex; margin-bottom: 8px; align-items: center; gap: 2ch; } " +
                "#keyword { flex: 2; border: 1px solid #ccc; border-radius: 8px; padding: 10px; font-size: 14px; } " +
                "#reverseBtn { background-color: #6c757d; color: white; border: none; padding: 10px; border-radius: 8px; cursor: pointer; font-size: 14px; } " +
                "#reverseBtn.active { background-color: #28a745; } " +
                "#searchBtn { background-color: #007bff; color: white; border: none; padding: 10px 15px; border-radius: 8px; cursor: pointer; font-size: 14px; } " +
                "#searchBtn:hover { background-color: #0056b3; } " +
                ".tab-container { display: flex; overflow-x: auto; margin: 8px 0; padding: 4px 0; background-color: #f8f9fa; border-radius: 8px; } " +
                ".tab-btn { flex-shrink: 0; background-color: #6c757d; color: white; border: none; padding: 8px 12px; margin: 0 2px; border-radius: 8px; cursor: pointer; font-size: 14px; white-space: nowrap; } " +
                ".tab-btn.active { background-color: #007bff; } " +
                "#results { margin-top: 10px; max-height: 70vh; overflow-y: auto; padding-right: 5px; } " +
                ".result-group { margin-bottom: 15px; } " +
                ".result-title { font-weight: bold; margin-bottom: 8px; color: #495057; font-size: 1.1em; cursor: pointer; padding: 10px; background-color: #e9ecef; border-radius: 8px; } " +
                ".result-item { background-color: #f8f9fa; padding: 12px; border: 1px solid #dee2e6; border-radius: 8px; margin-bottom: 8px; cursor: pointer; font-size: 14px; display: none; } " +
                ".result-item:hover { background-color: #e9ecef; } " +
                ".result-info { color: #6c757d; font-size: 0.9em; margin-top: 4px; } " +
                ".no-results { text-align: center; padding: 20px; color: #6c757d; } " +
                "</style></head><body>" +
                "<div class='container'>" +
                "<h1>弹幕搜索</h1>" +
                "<div class='search-box'>" +
                "<button id='searchBtn' onclick='search()'>搜索</button>" +
                "<input type='text' id='keyword' placeholder='输入关键词搜索弹幕...'>" +
                "<button id='reverseBtn' onclick='toggleOrder()'>正序</button>" +
                "</div>" +
                "<div class='tab-container' id='tabContainer'></div>" +
                "<div id='results'></div>" +
                "</div>" +
                "<script>" +
                "let isReversed = false;" +
                "let groupedResults = {};" +
                "let currentTab = '';" +
                "" +
                "function toggleOrder() {" +
                "  isReversed = !isReversed;" +
                "  const reverseBtn = document.getElementById('reverseBtn');" +
                "  reverseBtn.textContent = isReversed ? '倒序' : '正序';" +
                "  reverseBtn.classList.toggle('active', isReversed);" +
                "  if (currentTab) {" +
                "    showResultsForTab(currentTab);" +
                "  }" +
                "}" +
                "" +
                "function search() {" +
                "  const keyword = document.getElementById('keyword').value.trim();" +
                "  if (!keyword) { alert('请输入关键词'); return; }" +
                "  const resultsDiv = document.getElementById('results');" +
                "  resultsDiv.innerHTML = '<div class=\\'no-results\\'>正在搜索...</div>';" +
                "  fetch('/search?keyword=' + encodeURIComponent(keyword))" +
                "    .then(response => response.json())" +
                "    .then(data => {" +
                "      groupedResults = {};" +
                "      data.forEach(item => {" +
                "        const from = item.from || '默认';" +
                "        if (!groupedResults[from]) groupedResults[from] = [];" +
                "        groupedResults[from].push(item);" +
                "      });" +
                "      renderTabs();" +
                "    })" +
                "    .catch(error => {" +
                "      console.error('搜索错误:', error);" +
                "      resultsDiv.innerHTML = '<div class=\\'no-results\\'>搜索失败: ' + error.message + '</div>';" +
                "    });" +
                "}" +
                "" +
                "function renderTabs() {" +
                "  const tabContainer = document.getElementById('tabContainer');" +
                "  tabContainer.innerHTML = '';" +
                "  const tabs = Object.keys(groupedResults).sort();" +
                "  if (tabs.length === 0) { document.getElementById('results').innerHTML = '<div class=\\'no-results\\'>未找到结果</div>'; return; }" +
                "  tabs.forEach((tabName, index) => {" +
                "    const tabBtn = document.createElement('button');" +
                "    tabBtn.className = 'tab-btn';" +
                "    tabBtn.textContent = tabName;" +
                "    tabBtn.onclick = () => {" +
                "      currentTab = tabName;" +
                "      document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));" +
                "      tabBtn.classList.add('active');" +
                "      showResultsForTab(tabName);" +
                "    };" +
                "    tabContainer.appendChild(tabBtn);" +
                "  });" +
                "  currentTab = tabs[0];" +
                "  tabContainer.children[0].classList.add('active');" +
                "  showResultsForTab(currentTab);" +
                "}" +
                "" +
                "function showResultsForTab(tabName) {" +
                "  const resultsDiv = document.getElementById('results');" +
                "  resultsDiv.innerHTML = '';" +
                "  let items = groupedResults[tabName] || [];" +
                "  if (isReversed) items = [...items].reverse();" +
                "  if (items.length === 0) { resultsDiv.innerHTML = '<div class=\\'no-results\\'>该来源下无结果</div>'; return; }" +
                "  const animeGroups = {};" +
                "  items.forEach(item => {" +
                "    const animeTitle = item.animeTitle || item.title;" +
                "    if (!animeGroups[animeTitle]) animeGroups[animeTitle] = [];" +
                "    animeGroups[animeTitle].push(item);" +
                "  });" +
                "  Object.keys(animeGroups).sort().forEach(animeTitle => {" +
                "    const groupDiv = document.createElement('div');" +
                "    groupDiv.className = 'result-group';" +
                "    const titleDiv = document.createElement('div');" +
                "    titleDiv.className = 'result-title';" +
                "    titleDiv.textContent = `${animeTitle} (${animeGroups[animeTitle].length}集)`;" +
                "    titleDiv.onclick = () => {" +
                "      const subItems = groupDiv.querySelectorAll('.result-item');" +
                "      subItems.forEach(subItem => {" +
                "        subItem.style.display = subItem.style.display === 'block' ? 'none' : 'block';" +
                "      });" +
                "    };" +
                "    groupDiv.appendChild(titleDiv);" +
                "    animeGroups[animeTitle].forEach(item => {" +
                "      const div = document.createElement('div');" +
                "      div.className = 'result-item';" +
                "      div.innerHTML = `<div>${item.title}</div><div class='result-info'>${item.epTitle || ''}</div>`;" +
                "      div.onclick = (e) => { e.stopPropagation(); select(item.epId); };" +
                "      groupDiv.appendChild(div);" +
                "    });" +
                "    resultsDiv.appendChild(groupDiv);" +
                "  });" +
                "}" +
                "" +
                "function select(epId) {" +
                "  fetch('/select?epId=' + epId)" +
                "    .then(response => {" +
                "      if (response.ok) { alert('弹幕推送成功!'); } " +
                "      else { response.text().then(text => alert('弹幕推送失败: ' + text)); }" +
                "    })" +
                "    .catch(error => { console.error('推送错误:', error); alert('推送失败: ' + error.message); });" +
                "}" +
                "</script>" +
                "</body></html>";
    }
}
