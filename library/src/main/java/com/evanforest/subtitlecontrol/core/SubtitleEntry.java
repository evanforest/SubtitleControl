package com.evanforest.subtitlecontrol.core;

/**
 * 字幕实体，封装了一行字幕的信息
 *
 * @author Evan
 * @date 2018/12/18
 */
public class SubtitleEntry {
    private int mId;
    private int mBeginTime;
    private String mSubBody;
    private int mEndTime;

    public SubtitleEntry(int id, int beginTime, String subtitle, int endTime) {
        mId = id;
        mBeginTime = beginTime;
        mSubBody = subtitle;
        mEndTime = endTime;
    }

    public int getId() {
        return mId;
    }

    public void setId(int id) {
        mId = id;
    }

    public String getSubBody() {
        return mSubBody;
    }

    public void setSubBody(String srtBody) {
        mSubBody = srtBody;
    }

    public int getBeginTime() {
        return mBeginTime;
    }

    public void setBeginTime(int beginTime) {
        mBeginTime = beginTime;
    }

    public int getEndTime() {
        return mEndTime;
    }

    public void setEndTime(int endTime) {
        mEndTime = endTime;
    }
}
