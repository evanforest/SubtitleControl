package com.evanforest.subtitlecontrol.srt;

import android.content.Context;
import android.util.SparseArray;

import com.evanforest.subtitlecontrol.core.SubtitleEntry;
import com.evanforest.subtitlecontrol.core.SubtitleProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * A SubtitleProvider use SrtHelper
 * {@link SubtitleProvider}
 * {@link SrtHelper}
 *
 * @author Evan
 * @date 2018/12/18
 */
public class SrtSubtitleProvider implements SubtitleProvider {
    private final static String TOP_SUBTITLE_HEAD_FLAG = "{\\an8}";
    private List<SubtitleEntry> mSubtitleEntries = new ArrayList<>();
    private SparseArray<SubtitleEntry> mRelatedTopSubEntries = new SparseArray<>();
    private String mSubtitleUrl = "";
    private boolean mIsTerminated = false;
    private static final int REPARSE_TIMES = 2;

    public SrtSubtitleProvider(String subtitleUrl) {
        mSubtitleUrl = subtitleUrl;
    }

    public SrtSubtitleProvider startParse() {
        initCatcher();
        if (!mIsTerminated) {
            //if parse srt succeed will return true,
            if (!SrtHelper.getInstance().parseSrt("", mSubtitleUrl)) {
                mSubtitleEntries.clear();
                mRelatedTopSubEntries.clear();
                for (int i = 0; i < REPARSE_TIMES; i++) {
                    if (SrtHelper.getInstance().parseSrt("", mSubtitleUrl)) {
                        break;
                    }
                }
            }
        }
        SrtHelper.getInstance().setSrtParsedInterceptor(null);
        return this;
    }

    public SrtSubtitleProvider startParseFromAsset(Context context, String subtitleLocalPath) {
        initCatcher();
        if (!mIsTerminated) {
            if (!SrtHelper.getInstance().parseSrtFromAssert(context, subtitleLocalPath)) {
                mSubtitleEntries.clear();
                mRelatedTopSubEntries.clear();
            }
        }
        SrtHelper.getInstance().setSrtParsedInterceptor(null);
        return this;
    }

    public void terminateParsing() {
        mIsTerminated = true;
        SrtHelper.getInstance().terminateParsing();
    }

    private void initCatcher() {
        SrtHelper.getInstance().setSrtParsedInterceptor(new SrtHelper.SrtParsedInterceptor() {
            @Override
            public boolean onSrtParsed(int id, int startTime, String srtBody, int endTime) {
                if (srtBody.startsWith(TOP_SUBTITLE_HEAD_FLAG)) {
                    String cleanSubBody = srtBody.substring(TOP_SUBTITLE_HEAD_FLAG.length(), srtBody.length());
                    int index = mSubtitleEntries.get(mSubtitleEntries.size() - 1).getId();
                    mRelatedTopSubEntries.put(index, new SubtitleEntry(id, startTime, cleanSubBody, endTime));
                } else {
                    mSubtitleEntries.add(new SubtitleEntry(id, startTime, srtBody, endTime));
                }
                return true;
            }
        });
    }

    public String getSubtitleUrl() {
        return mSubtitleUrl;
    }

    @Override
    public SubtitleEntry get(int index) {
        return mSubtitleEntries.size() > index ? mSubtitleEntries.get(index) : null;
    }

    @Override
    public SubtitleEntry getRelatedTopSub(int id) {
        return mRelatedTopSubEntries.get(id);
    }

    @Override
    public int getTotalCount() {
        return mSubtitleEntries.size();
    }
}
