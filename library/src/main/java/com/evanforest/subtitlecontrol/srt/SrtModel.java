package com.evanforest.subtitlecontrol.srt;

/**
 * @author Evan
 * @date 2018/12/18
 */
public class SrtModel {
    private int mId;
    private int mBeginTime;
    private String mSrtBody = "";
    private int mEndTime;

    public int getId() {
        return mId;
    }

    public void setId(int id) {
        mId = id;
    }

    public String getSrtBody() {
        return mSrtBody;
    }

    public void setSrtBody(String srtBody) {
        mSrtBody = srtBody;
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
