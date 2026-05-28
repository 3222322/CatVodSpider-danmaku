package com.github.catvod.spider;

import android.app.Activity;
import com.github.catvod.spider.entity.DanmakuItem;
import com.google.gson.Gson;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class WebServer extends NanoHTTPD {

    public WebServer(int port) throws IOException {
        super(port);
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri.equals("/")) {
            return newFixedLengthResponse(getHtml());
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
