package com.yuedong.course.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.course.model.dto.VideoProgressDTO;
import com.yuedong.course.model.vo.VideoVO;
import com.yuedong.course.service.VideoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 跟练视频接口（列表/详情免登录；进度上报需登录）。
 */
@RestController
@RequestMapping("/video")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;

    /** 视频列表：已登录时附带当前用户的学习进度 */
    @GetMapping("/list")
    public Result<List<VideoVO>> list() {
        return Result.ok(videoService.list(UserContext.getUserId()));
    }

    /** 视频详情 */
    @GetMapping("/detail")
    public Result<VideoVO> detail(@RequestParam Long id) {
        return Result.ok(videoService.detail(id, UserContext.getUserId()));
    }

    /**
     * 上报播放进度（前端每推进 20% 调一次）。
     *
     * @return finished=是否完课；points=本次发放的积分（未触发完课为 null）
     */
    @PostMapping("/progress")
    public Result<VideoService.VideoProgressResult> progress(@Valid @RequestBody VideoProgressDTO dto) {
        return Result.ok(videoService.reportProgress(UserContext.getUserId(), dto));
    }
}
