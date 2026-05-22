package com.github.catvod.spider.entity;

import android.text.TextUtils;

/**
 * 媒体信息 http://127.0.0.1:9978/media
 */
public class Media {
    /**
     * 播放地址
     */
    private String url;

    /**
     * 播放状态
     */
    private Integer state;

    /**
     * 播放速度
     */
    private float speed;

    /**
     * 播放标题
     */
    private String title;

    /**
     * 正在播放：{{文件名}}
     */
    private String artist;

    /**
     * 封面图片
     */
    private String artwork;

    /**
     * 播放时长
     */
    private long duration;

    /**
     * 播放进度
     */
    private long position;

    public boolean isPlaying() {
        // 状态为2或3表示正在播放，或者有播放地址且播放时长和进度都大于0也认为在播放
        if (state != null) {
            return state == 2 || state == 3;
        }  else {
            return !TextUtils.isEmpty(url) && duration > 0 && position > 0;
        }
    }


    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Integer getState() {
        return state;
    }

    public void setState(Integer state) {
        this.state = state;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = speed;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getArtwork() {
        return artwork;
    }

    public void setArtwork(String artwork) {
        this.artwork = artwork;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
    }
}
