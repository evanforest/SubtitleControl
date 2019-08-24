package com.evanforest.subtitlecontrol.core;

/**
 * 字幕提供者接口，负责为SubtitleDirector提供字幕数据
 * 该接口实现了SubtitleDirector与字幕数据源之间的解耦
 * 你可以通过实现本接口，自定义不同的字幕数据来源方式
 *
 * @author Evan
 * @date 2018/12/17
 */
public interface SubtitleProvider {
    SubtitleEntry get(int index);

    /**
     * 获取当前字幕关联的顶部字幕
     *
     * @param id 当前字幕id
     * @return 顶部字幕
     */
    SubtitleEntry getRelatedTopSub(int id);

    int getTotalCount();

}
