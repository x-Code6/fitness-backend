package com.yuedong.course.service;

import com.yuedong.course.model.dto.VideoProgressDTO;
import com.yuedong.course.model.vo.VideoProgressVO;
import com.yuedong.course.model.vo.VideoVO;

import java.util.List;

/**
 * 跟练视频业务接口。
 */
public interface VideoService {

    /** 视频列表（userId 可为 null：匿名访问时不返回进度） */
    List<VideoVO> list(Long userId);

    /** 视频详情 */
    VideoVO detail(Long videoId, Long userId);

    /**
     * 上报播放进度。
     *
     * @return finished=本次上报后是否完课；points=本次发放的积分（未触发完课为 null）
     */
    VideoProgressVO reportProgress(Long userId, VideoProgressDTO dto);
}
