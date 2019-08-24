package com.evanforest.subtitlecontrol.srt;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 解析srt格式字幕文件 *.srt，是非异步的
 *
 * @author Evan
 * @date 2018/12/18
 */
public class SrtHelper {
    public static final String TAG = "SrtHelper";
    private static final String UTF_8 = "UTF-8";
    private static final int TIME_OUT = 15000;
    private final static String EQUAL_STRING_EXPRESS = "\\d\\d:\\d\\d:\\d\\d,\\d\\d\\d --> \\d\\d:\\d\\d:\\d\\d,\\d\\d\\d";
    private volatile static SrtHelper mSrcHelper;
    private volatile static boolean mIsTerminated = false;
    private List<SrtModel> mSrtModelList = new ArrayList<>();
    private final Object mLock = new Object();
    private SrtParsedInterceptor mInterceptor = null;

    private SrtHelper() {

    }

    public static SrtHelper getInstance() {
        if (mSrcHelper == null) {
            synchronized (SrtHelper.class) {
                if (mSrcHelper == null) {
                    mSrcHelper = new SrtHelper();
                }
            }
        }
        return mSrcHelper;
    }

    public int getStrModelCount() {
        return mSrtModelList.size();
    }

    public void clearSrtModeList() {
        if (mSrtModelList != null) {
            mSrtModelList.clear();
        }
    }

    public String getTimeText(int timeMils) {
        String currentTimeTxt = "";
        Log.i(TAG, " getTimeText-------------" + timeMils);
        synchronized (mLock) {
            if (mSrtModelList != null && !mSrtModelList.isEmpty()) {
                SrtModel srtModel = searchSub(mSrtModelList, timeMils);
                if (srtModel != null) {
                    currentTimeTxt = srtModel.getSrtBody();
                }
            }
        }
        return currentTimeTxt;
    }

    public int getTimeTextIndex(int timeMils) {
        int index = -1;
        synchronized (mLock) {
            if (mSrtModelList != null && !mSrtModelList.isEmpty()) {
                index = getSubtitleIndex(mSrtModelList, timeMils);
            }
        }
        return index;
    }

    public SrtModel getCurrentSrtByTime(int timeMils) {
        return get(getTimeTextIndex(timeMils));
    }

    public SrtModel getNextSrtByTime(int timeMils) {
        return get(getTimeTextIndex(timeMils) + 1);
    }

    public SrtModel get(int index) {
        synchronized (mLock) {
            if (mSrtModelList != null && !mSrtModelList.isEmpty() && index < mSrtModelList.size() && index > -1) {
                return mSrtModelList.get(index);
            }
        }
        return new SrtModel();
    }

    public List<String> getSrtStringList() {
        List<String> list = new ArrayList<>();
        synchronized (mLock) {
            if (mSrtModelList != null && !mSrtModelList.isEmpty()) {
                for (SrtModel srtModel : mSrtModelList) {
                    list.add(srtModel.getSrtBody());
                }
            }
        }
        return list;
    }

    public void terminateParsing() {
        Log.d(TAG, "terminateParsing: ");
        ;
        mIsTerminated = true;
    }

    /**
     * parse subtitle from url
     *
     * @param language
     * @param timeTextUrl
     * @return the parsed subtitle is completed
     */
    public synchronized boolean parseSrt(String language, final String timeTextUrl) {
        mIsTerminated = false;
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            Log.d(TAG, "parseSrt: timeTextUrl = " + encodeUrl(timeTextUrl));
            URL url = new URL(encodeUrl(timeTextUrl));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIME_OUT);
            in = connection.getInputStream();
            return parseSrtInputStream(in);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (connection != null) {
                    connection.disconnect();
                }
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    public synchronized boolean parseSrtFromAssert(Context context, String srtPath) {
        mIsTerminated = false;
        InputStream in = null;
        try {
            in = context.getResources().getAssets().open(srtPath);
            return parseSrtInputStream(in);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    public synchronized boolean parseSrtInputStream(InputStream srtIn) {
        BufferedReader br = null;
        boolean isCompleted = false;
        try {
            Log.d(TAG, "parseSrtInputStream: start");
            synchronized (mLock) {
                if (mSrtModelList == null) {
                    mSrtModelList = new ArrayList<>();
                } else {
                    mSrtModelList.clear();
                }
            }
            br = new BufferedReader(new InputStreamReader(srtIn));
            String line;
            List<String> parsedStrings = new ArrayList<>();
            while (!mIsTerminated && (line = br.readLine()) != null) {
                //Log.d("evan_debug", "parseSrtInputStream: line = " + line);
                if (!line.isEmpty()) {
                    parsedStrings.add(line);
                    continue;
                }
                if (parsedStrings.size() < 3) {
                    parsedStrings.clear();
                    continue;
                }
                final int id = getIdFromString(parsedStrings.get(0));
                int timeIndex = getTimeIndex(parsedStrings);
                if (timeIndex == -1) {
                    parsedStrings.clear();
                    continue;
                }
                String timeStr = parsedStrings.get(timeIndex);
                int begin_hour = Integer.parseInt(timeStr.substring(0, 2));
                int begin_minutes = Integer.parseInt(timeStr.substring(3, 5));
                int begin_second = Integer.parseInt(timeStr.substring(6, 8));
                int begin_milli = Integer.parseInt(timeStr.substring(9, 12));
                final int beginTime = (begin_hour * 3600 + begin_minutes * 60 + begin_second) * 1000 + begin_milli;

                int end_hour = Integer.parseInt(timeStr.substring(17, 19));
                int end_minutes = Integer.parseInt(timeStr.substring(20, 22));
                int end_second = Integer.parseInt(timeStr.substring(23, 25));
                int end_milli = Integer.parseInt(timeStr.substring(26, 29));
                final int endTime = (end_hour * 3600 + end_minutes * 60 + end_second) * 1000 + end_milli;

                StringBuilder srtBody = new StringBuilder();
                for (int i = timeIndex + 1; i < parsedStrings.size(); i++) {
                    srtBody.append(parsedStrings.get(i)).append("\n");
                }
                srtBody = new StringBuilder(srtBody.substring(0, srtBody.length() - 1));
                final String srtContent = new String(srtBody.toString().getBytes(), UTF_8);
                if (mInterceptor == null || !mInterceptor.onSrtParsed(id, beginTime, srtContent, endTime)) {
                    SrtModel srt = new SrtModel();
                    srt.setId(id);
                    srt.setBeginTime(beginTime);
                    srt.setSrtBody(srtContent);
                    srt.setEndTime(endTime);
                    synchronized (mLock) {
                        mSrtModelList.add(srt);
                    }
                }
                parsedStrings.clear();
            }
            if (!mIsTerminated) {
                isCompleted = true;
            } else {
                Log.d(TAG, "parseSrtInputStream: terminated");
            }
        } catch (IOException e) {
            Log.d(TAG, "parseSrtInputStream: exception");
            e.printStackTrace();
        } finally {
            Log.d(TAG, "parseSrtInputStream: end");
            mInterceptor = null;
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return isCompleted;
    }

    private String encodeUrl(String urlPath) {
        if (TextUtils.isEmpty(urlPath)) {
            return "";
        }
        try {
            URL url = new URL(urlPath);
            URI uri = new URI(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), url.getPath(), url.getQuery(), url.getRef());
            return uri.toURL().toString();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return urlPath;
    }

    private int getIdFromString(String idString) {
        int start = -1;
        int count = -1;
        for (int i = 0; i < idString.length(); i++) {
            if (idString.charAt(i) >= '0' && idString.charAt(i) <= '9') {
                if (start == -1) {
                    start = i;
                }
            } else {
                if (start != -1) {
                    count = i - start;
                    break;
                }
            }
        }
        if (start == -1) {
            return 0;
        }
        String trim;
        if (count == -1) {
            trim = idString.substring(start);
        } else {
            trim = idString.substring(start, start + count);
        }
        return Integer.valueOf(trim);
    }

    private int getTimeIndex(List<String> parsedStr) {
        int index = -1;
        for (int i = 0; i < parsedStr.size(); i++) {
            if (Pattern.matches(EQUAL_STRING_EXPRESS, parsedStr.get(i))) {
                index = i;
                break;
            }
        }
        return index;
    }

    public void setSrtParsedInterceptor(SrtParsedInterceptor interceptor) {
        mInterceptor = interceptor;
    }

    public interface SrtParsedInterceptor {
        boolean onSrtParsed(int id, int startTime, String srtBody, int endTime);
    }

    /**
     * dichotomy to search subtitle
     *
     * @param subtitles subtitlelist
     * @param playTime  playtime
     * @return
     */
    public SrtModel searchSub(List<SrtModel> subtitles, int playTime) {
        int start = 0;
        int end = subtitles.size() - 1;
        while (start <= end) {
            int middle = (start + end) / 2;
            if (playTime < subtitles.get(middle).getBeginTime()) {
                if (playTime > subtitles.get(middle).getEndTime()) {
                    return subtitles.get(middle);
                }
                end = middle - 1;
            } else if (playTime > subtitles.get(middle).getEndTime()) {
                if (playTime < subtitles.get(middle).getBeginTime()) {
                    return subtitles.get(middle);
                }
                start = middle + 1;
            } else if (playTime >= subtitles.get(middle).getBeginTime() && playTime <= subtitles.get(middle).getEndTime()) {
                return subtitles.get(middle);
            }
        }
        int index = getSubtitleIndex(subtitles, playTime);
        return index != -1 ? subtitles.get(index) : new SrtModel();
    }

    public int getSubtitleIndex(List<SrtModel> subtitles, int playTime) {
        int start = 0;
        int end = subtitles.size() - 1;
        while (start <= end) {
            int middle = (start + end) / 2;
            if (playTime < subtitles.get(middle).getBeginTime()) {
                if (playTime > subtitles.get(middle).getEndTime()) {
                    return middle;
                }
                end = middle - 1;
            } else if (playTime > subtitles.get(middle).getEndTime()) {
                if (playTime < subtitles.get(middle).getBeginTime()) {
                    return middle;
                }
                start = middle + 1;
            } else if (playTime >= subtitles.get(middle).getBeginTime() && playTime <= subtitles.get(middle).getEndTime()) {
                return middle;
            }
        }
        return -1;
    }

}