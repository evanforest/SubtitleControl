package com.evanforest.subtitlecontrol.core;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 字幕导演类
 * <p>
 * 描述：支持控制自动运行的字幕，即没有外部时间戳的字幕，如直播字幕；
 * 也支持通过外部时间戳的来定位的字幕，如点播字幕。
 * <p>
 * 使用：SubtitleDirector是一个抽象类，使用时创建子类并实现抽象方法，
 * 通过getController()获得控制器，用于控制字幕的运行。
 * <p>
 * 控制模式：开始，停止，暂停，恢复，休眠，唤醒，定位跳转。
 * <p>
 * 刷新原理：不是采用固定时间间隔的方式来刷新字幕，
 * 而是字幕根据其开始时间显示，根据其结束时间开始等待下一字幕。
 * 字幕显示时机更加精确；刷新性能更好，不做无用的刷新。
 * <p>
 * 自动校正字幕：会对线程阻塞的等原因造成的刷新滞后进行校正，直接显示滞后时间戳的字幕,
 * 这可能会造成字幕丢失，但保证了字幕整体时间轴不产生大偏差。
 * <p>
 * 最小字幕显示时间：当字幕时间小于该值，则跳过不显示，提前等待下一字幕，因此也会产生字幕丢失
 * （将显示时间并入下一字幕的等待时间，不影响整体时间轴）
 * <p>
 * 最小字幕等待时间：当等待下一字幕的时间小于该值，则不等待，提前显示下一字幕，
 * （将等待时间并入下一字幕的显示时间，不影响整体时间轴）
 * <p>
 * {@link SubtitleProvider}
 * {@link SubtitleEntry}
 *
 * @author Evan
 * @date 2018/12/13
 */
public abstract class SubtitleDirector {
    private static final String TAG = "SubtitleDirector";

    private static final int MIN_WAIT_MILLS = 20;
    private static final int MIN_DURATION_MILLS = 200;

    public static final int STATUS_NOT_START = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_PAUSE = 2;
    public static final int STATUS_SLEEP = 3;
    public static final int STATUS_FINISH = 4;

    private SubtitleProvider mSubtitleProvider = null;
    private SubHandler mSubHandler = null;
    private OnSubtitleTimerListener mTimerListener = null;
    private OnStatusChangedListener mStatusChangedListener = null;
    private Controller mController = null;

    /**
     * 默认不使用外部时间戳
     */
    private boolean mIsUseExternalTimestamp = false;

    /**
     * 对外部时间戳的偏移
     */
    private int mExternalTimestampOffsetMillis = 0;

    /**
     * 开始执行定时器响应函数的时间
     */
    private long mHandleStartTimeMillis = 0;

    private int mCurrentStatus = STATUS_NOT_START;
    private int mCurrentMillis = 0;
    private int mCurrentSubIndex = -1;

    public SubtitleDirector() {
        mSubHandler = new SubHandler(this);
        mController = new Controller();
    }

    public synchronized Controller getController() {
        return mController;
    }

    public int getCurrentStatus() {
        return mCurrentStatus;
    }

    private void setCurrentStatus(int newStatus) {
        int lastStatus = mCurrentStatus;
        mCurrentStatus = newStatus;
        if (mStatusChangedListener != null && lastStatus != newStatus) {
            mStatusChangedListener.onStatusChanged(lastStatus, newStatus);
        }
    }

    /**
     * 获取当前的字幕index
     *
     * @return 当前字幕索引，若未开始播放字幕，返回-1
     */
    public int getCurrentIndex() {
        return mCurrentSubIndex;
    }

    /**
     * 获取字幕提供者
     *
     * @return 字幕提供者，有可能为null
     */
    public SubtitleProvider getProvider() {
        return mSubtitleProvider;
    }

    /**
     * 获取字幕的当前时间戳
     *
     * @return 当前时间戳
     */
    public int getCurrentMillis() {
        return mIsUseExternalTimestamp ? getExternalCurrentTime() : mCurrentMillis + mSubHandler.getTimerEffectiveElapseTime();
    }

    /**
     * 获取整体字幕的持续期
     *
     * @return int[0]：整体起始时间，int[1]：整体结束时间
     */
    public int[] getTotalSubtitleDuration() {
        if (mSubtitleProvider == null || mSubtitleProvider.getTotalCount() <= 0) {
            return new int[]{0, 0};
        }
        return new int[]{mSubtitleProvider.get(0).getBeginTime(), mSubtitleProvider.get(mSubtitleProvider.getTotalCount() - 1).getEndTime()};
    }

    public void setTimerListener(OnSubtitleTimerListener listener) {
        mTimerListener = listener;
    }

    public OnSubtitleTimerListener getTimerListener() {
        return mTimerListener;
    }

    public void setOnStatusChangedListener(OnStatusChangedListener listener) {
        mStatusChangedListener = listener;
    }

    public OnStatusChangedListener getStatusChangedListener() {
        return mStatusChangedListener;
    }

    public int getExternalTimestampOffsetMillis() {
        return mExternalTimestampOffsetMillis;
    }

    final public boolean isUseExternalTimestamp() {
        return mIsUseExternalTimestamp;
    }

    /**
     * 设置是否启用外部时间戳，必须在启动字幕前调用
     *
     * @param isUseExternalTimestamp 是否启用
     */
    final public void setIsUseExternalTimestamp(boolean isUseExternalTimestamp) {
        if (mCurrentStatus == STATUS_NOT_START) {
            mIsUseExternalTimestamp = isUseExternalTimestamp;
        }
    }

    private String getTimeFormat(int timeMills) {
        Date date = new Date(timeMills);
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss,SSS", Locale.getDefault());
        return format.format(date);
    }

    /**
     * 处理外部时间戳字幕的定时器响应
     */
    private void handleExternalElapseTime() {
        handleElapseTime(0, 0);
    }

    /**
     * 核心代码，处理定时器的响应，根据流逝的时间选择字幕的显示
     *
     * @param realElapseTime   实际流逝的时间
     * @param expectElapseTime 预期流逝的时间
     */
    private void handleElapseTime(final int realElapseTime, final int expectElapseTime) {
        Log.d(TAG, "handleElapseTime: -----------------------------------------------");
        Log.d(TAG, "handleElapseTime: expectElapseTime = " + getTimeFormat(expectElapseTime));
        Log.d(TAG, "handleElapseTime: overTime = " + getTimeFormat(realElapseTime - expectElapseTime));
        mHandleStartTimeMillis = System.currentTimeMillis();
        if (mIsUseExternalTimestamp) {
            mCurrentMillis = getExternalCurrentTime() + mExternalTimestampOffsetMillis;
        } else {
            mCurrentMillis += realElapseTime;
        }

        if (mCurrentMillis < 0) {
            mCurrentMillis = 0;
        }

        Log.d(TAG, "handleElapseTime: mCurrentMillis = " + mCurrentMillis);

        if (mTimerListener != null) {
            mTimerListener.onOnceTimerDone(mCurrentMillis);
        }

        //往前寻找字幕
        if (mCurrentSubIndex != -1) {
            SubtitleEntry currentSubtitle = mSubtitleProvider.get(mCurrentSubIndex);
            if (mCurrentMillis < currentSubtitle.getBeginTime()) {
                if (mCurrentSubIndex == 0) {
                    mCurrentSubIndex = -1;
                    handleElapseTime(0, 0);
                    return;
                }
                SubtitleEntry previousSubtitle = getPreviousSubtitleEntry(mCurrentSubIndex);
                if (previousSubtitle != null) {
                    if (mCurrentMillis >= previousSubtitle.getEndTime()) {
                        mCurrentSubIndex--;
                        waitNextSubtitle(currentSubtitle.getBeginTime() - mCurrentMillis);
                    } else if (mCurrentMillis >= previousSubtitle.getBeginTime()) {
                        mCurrentSubIndex--;
                        durationSubtitle(previousSubtitle);
                    } else {
                        correctSubtitle(0, mCurrentSubIndex - 1);
                    }
                    return;
                }

            } else if (mCurrentMillis < currentSubtitle.getEndTime()) {
                durationSubtitle(currentSubtitle);
                return;
            }
        }

        //往后寻找字幕
        SubtitleEntry nextSubtitle = getNextSubtitleEntry(mCurrentSubIndex);
        if (nextSubtitle != null) {
            if (mCurrentMillis < nextSubtitle.getBeginTime()) {
                waitNextSubtitle(nextSubtitle.getBeginTime() - mCurrentMillis);
            } else if (mCurrentMillis <= nextSubtitle.getEndTime()) {
                mCurrentSubIndex++;
                durationSubtitle(nextSubtitle);
            } else {
                // find the correct subtitle behind current index.
                correctSubtitle(mCurrentSubIndex + 1, mSubtitleProvider.getTotalCount() - 1);
            }
        } else {
            finishDirectSubtitle();
        }
    }

    private SubtitleEntry getNextSubtitleEntry(int currentSubIndex) {
        if (mSubtitleProvider != null && mSubtitleProvider.getTotalCount() != 0 && currentSubIndex >= -1 && currentSubIndex < mSubtitleProvider.getTotalCount() - 1) {
            return mSubtitleProvider.get(currentSubIndex + 1);
        }
        return null;
    }

    private SubtitleEntry getPreviousSubtitleEntry(int currentSubIndex) {
        if (mSubtitleProvider.getTotalCount() != 0 && currentSubIndex > 0 && currentSubIndex < mSubtitleProvider.getTotalCount()) {
            return mSubtitleProvider.get(currentSubIndex - 1);
        }
        return null;
    }

    /**
     * 开始等待下一个字幕
     *
     * @param waitTime 等待时长
     * @return 是否进入等待
     */
    private boolean waitNextSubtitle(int waitTime) {
        if (waitTime < 0) {
            return false;
        }
        if (waitTime >= getMinWaitMills()) {
            mSubHandler.sendWaitNextSubtitleMsg(waitTime);
            onWaitingNextSubtitle(waitTime);
            return true;
        } else {
            mCurrentSubIndex++;
            durationSubtitle(mSubtitleProvider.get(mCurrentSubIndex));
            return false;
        }
    }

    /**
     * 持续显示当前的字幕
     *
     * @param durationSubtitle 当前字幕
     */
    private void durationSubtitle(SubtitleEntry durationSubtitle) {
        int availableDuration = durationSubtitle.getEndTime() - mCurrentMillis;
        if (availableDuration > getMinDurationMills()) {
            String top = "";
            SubtitleEntry topSubtitle = mSubtitleProvider.getRelatedTopSub(durationSubtitle.getId());
            if (topSubtitle != null) {
                top = topSubtitle.getSubBody();
            }
            mSubHandler.sendDurationSubtitleMsg(availableDuration);
            onRefreshSubtitle(durationSubtitle.getSubBody(), top);
        } else {
            skipDurationToWaitNextSubtitle(mCurrentSubIndex);
        }
    }

    /**
     * 跳过字幕，等待下一个字幕
     *
     * @param skippedIndex 被跳过的字幕索引
     */
    private void skipDurationToWaitNextSubtitle(int skippedIndex) {
        SubtitleEntry nextSubtitle = getNextSubtitleEntry(skippedIndex);
        if (nextSubtitle != null) {
            mCurrentSubIndex = skippedIndex;
            onLossSubtitle(skippedIndex, 1);
            mSubHandler.sendWaitNextSubtitleMsg(nextSubtitle.getBeginTime() - mCurrentMillis);
            onWaitingNextSubtitle(nextSubtitle.getBeginTime() - mCurrentMillis);
        } else {
            finishDirectSubtitle();
        }
    }

    private void finishDirectSubtitle() {
        //keepAlive的作用是始终保持一个定时器存活，用于监听外部时间戳的变化
        //由于添加了refreshToGetExternalTimestamp可以手动去刷新获取外部字幕，所以不再使用keepAlive
        finishDirectSubtitle(false);
    }

    /**
     * 结束对字幕的导演
     *
     * @param keepAlive 是否保持定时器存活
     */
    private void finishDirectSubtitle(boolean keepAlive) {
        setCurrentStatus(STATUS_FINISH);
        mSubHandler.reset();
        mHandleStartTimeMillis = 0;
        mCurrentMillis = mSubtitleProvider.get(mSubtitleProvider.getTotalCount() - 1).getEndTime();
        mController.mSleepStartTimeMillis = 0;
        mCurrentSubIndex = mSubtitleProvider.getTotalCount() - 1;
        onSubtitleFinish();
        //在使用外部时间戳字幕时keepAlive才生效，作用是保持始终能获取到外部的时间戳
        if (mIsUseExternalTimestamp && keepAlive) {
            mSubHandler.sendKeepAliveMsg();
        }
    }

    /**
     * 校正字幕，根据mCurrentMillis在[startIndex，endIndex]内定位到正确的字幕
     *
     * @param startIndex 校正起始索引
     * @param endIndex   校正结束索引
     */
    private void correctSubtitle(int startIndex, int endIndex) {
        Log.d(TAG, "correctSubtitle: ");
        if (mCurrentMillis < mSubtitleProvider.get(0).getBeginTime()) {
            mCurrentSubIndex = -1;
            handleElapseTime(0, 0);
            return;
        }
        if (mCurrentMillis >= mSubtitleProvider.get(mSubtitleProvider.getTotalCount() - 1).getEndTime()) {
            finishDirectSubtitle();
            return;
        }
        int correctIndex = searchSubtitleIndex(startIndex, endIndex, mCurrentMillis, false);
        mCurrentSubIndex = correctIndex;
        if (mCurrentSubIndex != -1) {
            SubtitleEntry correctSubtitle = mSubtitleProvider.get(mCurrentSubIndex);
            if (mCurrentMillis <= correctSubtitle.getEndTime()) {
                durationSubtitle(correctSubtitle);
            } else {
                //this waitTime must be greater than 0
                waitNextSubtitle(mSubtitleProvider.get(mCurrentSubIndex + 1).getBeginTime() - mCurrentMillis);
            }
        } else {
            finishDirectSubtitle();
        }
    }

    /**
     * 使用二分法查找字幕
     *
     * @param startIndex 起始索引
     * @param endIndex   结束索引
     * @param playTime   查找的时间
     * @param ceil       当时间处于两个字幕之间的等待时间时，
     *                   ceil决定是否向后取字幕{@true 向后取字幕}，{@false 向前取字幕}
     * @return 字幕所在的索引，找不到字幕时返回-1，
     * 找不到的情况为：小于第一个索引的起始时间或大于结束索引的结束时间
     */
    private int searchSubtitleIndex(int startIndex, int endIndex, int playTime, boolean ceil) {
        if (endIndex < startIndex || startIndex < 0 || endIndex < 0 || endIndex >= mSubtitleProvider.getTotalCount()) {
            throw new RuntimeException("illegality startIndex or endIndex, startIndex = " + startIndex + ", endIndex =" + endIndex);
        }
        int start = startIndex;
        int end = endIndex;
        while (start <= end) {
            int middle = (start + end) / 2;
            SubtitleEntry middleSubtitle = mSubtitleProvider.get(middle);
            if (playTime >= middleSubtitle.getBeginTime() && playTime <= middleSubtitle.getEndTime()) {
                return middle;
            }
            if (playTime < middleSubtitle.getBeginTime()) {
                if (middle - 1 >= start && mSubtitleProvider.get(middle - 1).getEndTime() < playTime) {
                    if (ceil) {
                        return middle;
                    } else {
                        return middle - 1;
                    }
                } else {
                    end = middle - 1;
                }
            } else if (playTime > middleSubtitle.getEndTime()) {
                if (middle + 1 <= end && mSubtitleProvider.get(middle + 1).getBeginTime() > playTime) {
                    if (ceil) {
                        return middle + 1;
                    } else {
                        return middle;
                    }
                } else {
                    start = middle + 1;
                }
            }
        }
        return -1;
    }

    /**
     * 子类可通过重写此方法修改【最小字幕等待时长】
     *
     * @return 最小字幕等待时长
     */
    protected int getMinWaitMills() {
        return MIN_WAIT_MILLS;
    }

    /**
     * 子类可通过重写此方法修改【最小字幕持续时长】
     *
     * @return 最小字幕持续时长
     */
    protected int getMinDurationMills() {
        return MIN_DURATION_MILLS;
    }

    /**
     * 丢失字幕时回调
     *
     * @param lossStartIndex 第一个丢失字幕的索引
     * @param count          总丢失个数
     */
    protected void onLossSubtitle(int lossStartIndex, int count) {
        Log.d(TAG, "onLossSubtitle: lossIndexCount = " + count + ", from " + lossStartIndex + " to " + (lossStartIndex + count + 1));
    }

    /**
     * 字幕开始时回调
     */
    abstract protected void onSubtitleStart();

    /**
     * 获取外部的时间戳时回调
     * 调用setIsUseExternalTimestamp(true)后此方法才生效
     *
     * @return 外部当前时间戳
     */
    abstract protected int getExternalCurrentTime();

    /**
     * 刷新字幕时回调
     *
     * @param subtitle    字幕
     * @param topSubtitle 顶部关联字幕
     */
    abstract protected void onRefreshSubtitle(String subtitle, String topSubtitle);

    /**
     * 等待下一个字幕时回调
     *
     * @param waitMills 等待时长
     */
    abstract protected void onWaitingNextSubtitle(int waitMills);

    /**
     * 字幕播放完毕时回调，定时器此时有可能还处于keepAlive
     */
    abstract protected void onSubtitleFinish();

    /**
     * SubtitleDirector定时监听器
     */
    public interface OnSubtitleTimerListener {
        /**
         * 当handler定时器回调时调用该方法
         *
         * @param currentMills 当前时间
         */
        void onOnceTimerDone(int currentMills);
    }

    /**
     * SubtitleDirector状态监听器
     */
    public interface OnStatusChangedListener {
        /**
         * 当SubtitleDirector状态改变时回调该方法
         *
         * @param lastStatus    改变前的状态
         * @param currentStatus 改变后的状态
         */
        void onStatusChanged(int lastStatus, int currentStatus);
    }

    /**
     * SubtitleDirector的控制器，负责SubtitleDirector的用户操作。
     */
    public class Controller {
        /**
         * sleep的起始时间
         */
        private long mSleepStartTimeMillis = 0;

        private Controller() {

        }

        public synchronized boolean start(SubtitleProvider provider) {
            return start(provider, 0);
        }

        /**
         * 启动字幕
         *
         * @param provider     字幕提供者
         * @param currentMills 起始时间
         * @return 是否启动成功
         */
        public synchronized boolean start(SubtitleProvider provider, int currentMills) {
            if (mCurrentStatus != STATUS_NOT_START && mCurrentStatus != STATUS_FINISH) {
                stop();
                start(provider, currentMills);
                Log.i(TAG, "start: This subtitle director has been started, cannot start again.");
//                return false;
            }
            if (provider == null || provider.getTotalCount() <= 0) {
                Log.i(TAG, "start: No any subtitle, start failed");
                return false;
            }
            if (currentMills < 0 || currentMills > provider.get(provider.getTotalCount() - 1).getEndTime()) {
                Log.i(TAG, "start: Current mills is out of the range of subtitles total duration. currentMills = " + currentMills);
                return false;
            }
            mSubtitleProvider = provider;
            setCurrentStatus(STATUS_RUNNING);
            mSubHandler.reset();
            mCurrentMillis = 0;
            mCurrentSubIndex = -1;
            mSleepStartTimeMillis = 0;
            mExternalTimestampOffsetMillis = 0;
            onSubtitleStart();
            handleElapseTime(currentMills, 0);
            return true;
        }

        /**
         * 停止字幕，与start对应。被此方法停止后，定时器不会保持存活。
         */
        public synchronized void stop() {
            if (mCurrentStatus == STATUS_NOT_START) {
                mSubHandler.reset();
                Log.i(TAG, "This subtitle director is not started, cannot be stopped! ");
                return;
            } else if (mCurrentStatus == STATUS_FINISH) {
                mSubHandler.reset();
                Log.i(TAG, "This subtitle director has already been finished, no need to stop it again! ");
                return;
            }
            finishDirectSubtitle(false);
        }

        /**
         * 暂停字幕，只能在正常运行时暂停
         */
        public synchronized void pause() {
            if (mCurrentStatus != STATUS_RUNNING) {
                Log.i(TAG, "This subtitle director is not running, cannot be paused! ");
                return;
            }
            setCurrentStatus(STATUS_PAUSE);
            mSubHandler.pauseTiming();
        }

        /**
         * 恢复字幕运行，与pause对应
         */
        public synchronized void resume() {
            if (mCurrentStatus != STATUS_PAUSE) {
                Log.i(TAG, "This subtitle director is not paused, cannot be resumed! ");
                return;
            }
            setCurrentStatus(STATUS_RUNNING);
            mSubHandler.resumeTiming();
        }

        /**
         * 进入休眠状态，停止handler计时并停止字幕刷新，直到唤醒字幕{@link Controller#wake()}。
         * 休眠的时长也会被算入字幕的流逝时间，这也是与暂停的区别所在。
         * 正常运行和暂停时都可进入休眠状态,暂停状态下进入休眠会结束暂停状态。
         * 一般用于进入其他界面时，将字幕休眠。
         */
        public synchronized void sleep() {
            if (mCurrentStatus == STATUS_SLEEP) {
                Log.i(TAG, "This subtitle director has gone to sleep, cannot sleep again. ");
                return;
            }
            if (mCurrentStatus == STATUS_RUNNING || mCurrentStatus == STATUS_PAUSE) {
                if (mCurrentStatus == STATUS_RUNNING) {
                    pause();
                }
                mSleepStartTimeMillis = System.currentTimeMillis() + mSubHandler.getTimerEffectiveElapseTime();
                setCurrentStatus(STATUS_SLEEP);
                mSubHandler.reset();
            }
        }

        /**
         * 唤醒sleep
         */
        public synchronized void wake() {
            if (mCurrentStatus != STATUS_SLEEP) {
                Log.i(TAG, "This subtitle director is not sleeping, cannot be waked! ");
                return;
            }
            setCurrentStatus(STATUS_RUNNING);
            final long sleepTime = System.currentTimeMillis() - mSleepStartTimeMillis;
            Log.d(TAG, "wake: sleepTime = " + getTimeFormat((int) sleepTime));
            handleElapseTime((int) sleepTime, 0);
            mSleepStartTimeMillis = 0;
        }

        public synchronized void seekTo(int expectMills) {
            seekBy(expectMills - mCurrentMillis);
        }

        /**
         * 根据偏移时间重新定位字幕当前位置，在字幕正常运行、暂停、休眠以及完成时都可调用
         * 运行时调用后字幕继续运行；暂停时调用后字幕依然暂停
         * 如果要频繁重复定位，最好先将字幕暂停，等定位操作结束后恢复运行
         *
         * @param offsetMills 偏移时间
         */
        public synchronized void seekBy(int offsetMills) {
            if (mCurrentStatus == STATUS_RUNNING || mCurrentStatus == STATUS_PAUSE) {
                int mElapseTime = mSubHandler.getTimerEffectiveElapseTime();
                Log.d(TAG, "seekBy: mElapseTime = " + mElapseTime);
                Log.d(TAG, "seekBy: offsetMills = " + offsetMills);

                mSubHandler.reset();
                Log.d(TAG, "seekBy: seekOffsetTime = " + getTimeFormat(offsetMills + mElapseTime));
                handleElapseTime(offsetMills + mElapseTime, 0);
            } else if (mCurrentStatus == STATUS_SLEEP) {
                mSleepStartTimeMillis -= offsetMills;
            } else if (mCurrentStatus == STATUS_FINISH) {
                if (offsetMills > 0) {
                    return;
                }
                setCurrentStatus(STATUS_RUNNING);
                handleElapseTime(offsetMills, 0);
            } else {
                Log.d(TAG, "seek: This subtitle director is not started, cannot seek.");
            }
        }

        /**
         * 设置外部时间戳的偏移量并刷新获取外部时间戳，在使用外部时间戳时才有用
         *
         * @param offsetMillis 外部时间戳偏移量
         */
        public synchronized void setExternalTimestampOffsetMillis(int offsetMillis) {
            if (!mIsUseExternalTimestamp) {
                Log.d(TAG, "seek: This subtitle director is not use external timestamp!");
                return;
            }
            mExternalTimestampOffsetMillis = offsetMillis;
            refreshToGetExternalTimestamp();
        }

        /**
         * 手动刷新去获取外部时间戳，有了此方法就不需要保持定时器keepAlive了。
         * 在外部时间戳发生大范围变化时调用(如seek时)，或者外部时间戳偏移量发生改变时。
         */
        public synchronized void refreshToGetExternalTimestamp() {
            if (!mIsUseExternalTimestamp) {
                Log.d(TAG, "seek: This subtitle director is not use external timestamp!");
                return;
            }
            if (mCurrentStatus == STATUS_RUNNING || mCurrentStatus == STATUS_PAUSE || mCurrentStatus == STATUS_FINISH) {
                if (mCurrentStatus == STATUS_FINISH) {
                    setCurrentStatus(STATUS_RUNNING);
                }
                mSubHandler.reset();
                handleExternalElapseTime();
            }
        }
    }

    private static class SubHandler extends Handler {
        private static final int MSG_WHAT_SUBTITLE_TIMER = 0;
        private static final int MSG_WHAT_EXTERNAL_FINISH_KEEP_ALIVE = 1;
        private static final int KEEP_ALIVE_INTERVAL_TIME = 500;

        private static final int MSG_STATUS_NONE = 0;
        private static final int MSG_STATUS_WAITING = 1;
        private static final int MSG_STATUS_DURATION = 2;

        private int mCurrentMsgState = MSG_STATUS_NONE;
        private long mStartTimingMillis = 0;
        private int mExpectTimingMillis = 0;

        private long mPauseStartMillis = 0;
        private long mPauseTotalMillis = 0;
        private int mResumeLeftTimingMillis = 0;

        private WeakReference<SubtitleDirector> mOwner;

        private SubHandler(SubtitleDirector owner) {
            mOwner = new WeakReference<>(owner);
        }

        private SubHandler(SubtitleDirector owner, Looper looper) {
            super(looper);
            mOwner = new WeakReference<>(owner);
        }

        public void reset() {
            removeMessages(MSG_WHAT_SUBTITLE_TIMER);
            removeMessages(MSG_WHAT_EXTERNAL_FINISH_KEEP_ALIVE);
            mCurrentMsgState = MSG_STATUS_NONE;
            mStartTimingMillis = 0;
            mExpectTimingMillis = 0;
            mResumeLeftTimingMillis = 0;
            mPauseStartMillis = 0;
            mPauseTotalMillis = 0;
        }

        /**
         * 暂停定时
         */
        public void pauseTiming() {
            if (mCurrentMsgState == MSG_STATUS_NONE) {
                return;
            }
            removeMessages(MSG_WHAT_SUBTITLE_TIMER);
            mPauseStartMillis = System.currentTimeMillis();
            //暂停时记录【总定时还剩余的时长】
            mResumeLeftTimingMillis = mExpectTimingMillis - getTimerEffectiveElapseTime();
        }

        /**
         * 恢复定时
         */
        public void resumeTiming() {
            if (mCurrentMsgState == MSG_STATUS_NONE || mPauseStartMillis == 0) {
                return;
            }
            removeMessages(MSG_WHAT_SUBTITLE_TIMER);
            //恢复时累加上【本次暂停的时长】到【总暂停时长】中
            mPauseTotalMillis += System.currentTimeMillis() - mPauseStartMillis;
            Message message = obtainMessage(MSG_WHAT_SUBTITLE_TIMER);
            //继续开始定时【总定时还剩余的时长】
            sendMessageDelayed(message, mResumeLeftTimingMillis);
            mResumeLeftTimingMillis = 0;
        }

        public void sendWaitNextSubtitleMsg(int waitTime) {
            mCurrentMsgState = MSG_STATUS_WAITING;
            sendTimer(waitTime);
        }

        public void sendDurationSubtitleMsg(int duration) {
            mCurrentMsgState = MSG_STATUS_DURATION;
            sendTimer(duration);
        }

        private void sendTimer(final int timingMills) {
            removeMessages(MSG_WHAT_SUBTITLE_TIMER);

            if (mOwner.get() == null || mOwner.get().mCurrentStatus == STATUS_SLEEP) {
                return;
            }
            mStartTimingMillis = System.currentTimeMillis();

            //计算出【handle函数执行花费的时间】，修正误差
            long handleSpendTime = mStartTimingMillis - mOwner.get().mHandleStartTimeMillis;
            Log.d(TAG, "sendTimer: handleSpendTime = " + handleSpendTime + ", timingMills = " + timingMills);

            //记录【本次定时预期的总时长】
            mExpectTimingMillis = handleSpendTime > timingMills ? 0 : timingMills - (int) handleSpendTime;
            if (mOwner.get().mCurrentStatus == STATUS_PAUSE) {
                //依然保持暂停状态
                mPauseStartMillis = System.currentTimeMillis();
                mResumeLeftTimingMillis = mExpectTimingMillis;
                return;
            }
            Message message = obtainMessage(MSG_WHAT_SUBTITLE_TIMER);
            sendMessageDelayed(message, mExpectTimingMillis);
        }

        /**
         * 发送消息让handler处于计时状态
         */
        public void sendKeepAliveMsg() {
            removeMessages(MSG_WHAT_EXTERNAL_FINISH_KEEP_ALIVE);
            sendEmptyMessageDelayed(MSG_WHAT_EXTERNAL_FINISH_KEEP_ALIVE, KEEP_ALIVE_INTERVAL_TIME);
        }

        /**
         * 计算有效的定时器流逝时间，不包括暂停的时间
         *
         * @return 有效的已流逝时间
         */
        private int getTimerEffectiveElapseTime() {
            long pauseTotalTimeMillis = mPauseTotalMillis;
            if (mOwner.get() != null && mOwner.get().mCurrentStatus == STATUS_PAUSE) {
                pauseTotalTimeMillis += (System.currentTimeMillis() - mPauseStartMillis);
            }
            Log.d(TAG, "getTimerEffectiveElapseTime: mPauseTotalMillis = " + mPauseTotalMillis);
            //定时器流逝时间等于【从开始计时到当前的总时长】减去【此过程中暂停的总时长】
            return mCurrentMsgState == MSG_STATUS_NONE ? 0 : (int) (System.currentTimeMillis() - mStartTimingMillis - pauseTotalTimeMillis);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WHAT_SUBTITLE_TIMER:
                    int elapseTime = getTimerEffectiveElapseTime();
                    mCurrentMsgState = MSG_STATUS_NONE;
                    mPauseTotalMillis = 0;
                    mPauseStartMillis = 0;
                    mStartTimingMillis = 0;
                    mResumeLeftTimingMillis = 0;
                    if (mOwner.get() != null) {
                        mOwner.get().handleElapseTime(elapseTime, mExpectTimingMillis);
                    }
                    break;
                case MSG_WHAT_EXTERNAL_FINISH_KEEP_ALIVE:
                    if (mOwner.get() != null) {
                        mOwner.get().setCurrentStatus(STATUS_RUNNING);
                        mOwner.get().handleExternalElapseTime();
                    }
                    break;
                default:
                    break;
            }
            super.handleMessage(msg);
        }
    }
}
