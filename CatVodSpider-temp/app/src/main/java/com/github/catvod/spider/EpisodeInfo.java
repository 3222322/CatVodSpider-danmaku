package com.github.catvod.spider;

import java.util.List;

public class EpisodeInfo {
    private String episodeNum;
    private List<String> episodeNames;
    private String episodeYear;
    private String episodeSeasonNum;
    private String seriesName;
    private String fileName;
    private String episodeUrl;

    public String getEpisodeNum() {
        return episodeNum;
    }

    public void setEpisodeNum(String episodeNum) {
        this.episodeNum = episodeNum;
    }

    public List<String> getEpisodeNames() {
        return episodeNames;
    }

    public void setEpisodeNames(List<String> episodeNames) {
        this.episodeNames = episodeNames;
    }

    public String getEpisodeYear() {
        return episodeYear;
    }

    public void setEpisodeYear(String episodeYear) {
        this.episodeYear = episodeYear;
    }

    public String getEpisodeSeasonNum() {
        return episodeSeasonNum;
    }

    public void setEpisodeSeasonNum(String episodeSeasonNum) {
        this.episodeSeasonNum = episodeSeasonNum;
    }

    public String getSeriesName() {
        return seriesName;
    }

    public void setSeriesName(String seriesName) {
        this.seriesName = seriesName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getEpisodeUrl() {
        return episodeUrl;
    }

    public void setEpisodeUrl(String episodeUrl) {
        this.episodeUrl = episodeUrl;
    }

    @Override
    public String toString() {
        return "EpisodeInfo{" +
                "episodeNum='" + episodeNum + '\'' +
                ", episodeNames=" + episodeNames +
                ", episodeYear='" + episodeYear + '\'' +
                ", episodeSeasonNum='" + episodeSeasonNum + '\'' +
                ", seriesName='" + seriesName + '\'' +
                ", fileName='" + fileName + '\'' +
                ", episodeUrl='" + episodeUrl + '\'' +
                '}';
    }
}
