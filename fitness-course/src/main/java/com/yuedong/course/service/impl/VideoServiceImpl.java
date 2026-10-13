package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.mapper.VideoMapper;
import com.yuedong.course.mapper.VideoProgressMapper;
import com.yuedong.course.mq.CourseEventPublisher;
import com.yuedong.course.model.dto.VideoProgressDTO;
import com.yuedong.course.model.entity.Video;
import com.yuedong.course.model.entity.VideoProgress;
import com.yuedong.course.model.vo.VideoVO;
import com.yuedong.course.service.VideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频业务实现：进度只增不减；首次达到 100% 判定完课并发 MQ 事件（发积分）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoServiceImpl implements VideoService {

    private final VideoMapper videoMapper;
    private final VideoProgressMapper videoProgressMapper;
    private final CourseEventPublisher eventPublisher;

    @Override
    public List<VideoVO> list(Long userId) {
        List<Video> videos = videoMapper.selectList(null);
        // 已登录用户：一次性查出该用户全部进度，内存组装（避免 N+1）
        Map<Long, Integer> progressMap = userId == null
                ? Map.of()
                : videoProgressMapper.selectList(new LambdaQueryWrapper<VideoProgress>()
                        .eq(VideoProgress::getUserId, userId)).stream()
                        .collect(HashMap::new, (m, p) -> m.put(p.getVideoId(), p.getProgress()), HashMap::putAll);

        return videos.stream()
                .map(v -> toVO(v, progressMap.get(v.getId())))
                .toList();
    }

    @Override
    public VideoVO detail(Long videoId, Long userId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND);
        }
        Integer progress = null;
        if (userId != null) {
            VideoProgress p = videoProgressMapper.selectOne(new LambdaQueryWrapper<VideoProgress>()
                    .eq(VideoProgress::getUserId, userId)
                    .eq(VideoProgress::getVideoId, videoId));
            progress = p == null ? 0 : p.getProgress();
        }
        return toVO(video, progress);
    }

    @Override
    public VideoProgressResult reportProgress(Long userId, VideoProgressDTO dto) {
        // ===== 第 1 步：视频存在性校验 =====
        Video video = videoMapper.selectById(dto.getVideoId());
        if (video == null) {
            throw new BusinessException(ErrorCode.VIDEO_NOT_FOUND);
        }

        // ===== 第 2 步：查当前进度记录 =====
        VideoProgress record = videoProgressMapper.selectOne(new LambdaQueryWrapper<VideoProgress>()
                .eq(VideoProgress::getUserId, userId)
                .eq(VideoProgress::getVideoId, dto.getVideoId()));

        boolean alreadyFinished = record != null && Boolean.TRUE.equals(record.getFinished());
        int oldProgress = record == null ? 0 : record.getProgress();
        int newProgress = Math.max(dto.getProgress(), oldProgress);

        // ===== 第 2.5 步：防刷速率校验 =====
        // 两次上报之间进度增量 > 真实耗时 × 容差系数 → 判定异常（拖进度条/伪造请求刷积分）。
        // 允许较大的首次容差：暂停后快进、退出兜底上报（间隔里包含关机时间）都可能有合理跳变，
        // 系数 1.5 + 15 秒宽限在"正常观看"下绝不会误伤，伪造请求则必然超限。
        if (record != null && record.getLastReportAt() != null) {
            long elapsedSeconds = java.time.Duration.between(
                    record.getLastReportAt(), LocalDateTime.now()).toSeconds();
            int maxIncrease = (int) (elapsedSeconds * 1.5) + 15;
            if (newProgress - oldProgress > maxIncrease) {
                log.warn("进度增长异常，已拒绝: userId={}, videoId={}, old={}, new={}, elapsed={}s",
                        userId, dto.getVideoId(), oldProgress, newProgress, elapsedSeconds);
                throw new BusinessException(ErrorCode.PARAM_ERROR, "进度异常，请正常观看视频");
            }
        }

        // ===== 第 3 步：无记录则插入（UNIQUE(user_id,video_id) 兜底并发首报） =====
        if (record == null) {
            record = new VideoProgress();
            record.setUserId(userId);
            record.setVideoId(dto.getVideoId());
            record.setProgress(newProgress);
            record.setFinished(newProgress >= 100);
            record.setLastReportAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            try {
                videoProgressMapper.insert(record);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首报：再走一次"只增更新"
            }
        }

        // ===== 第 4 步：进度只增不减（advance 的 WHERE 带 progress<newProgress） =====
        if (newProgress > oldProgress) {
            videoProgressMapper.advance(userId, dto.getVideoId(), newProgress);
        } else {
            // 进度没涨（心跳/兜底重报）：只刷新上报时间
            videoProgressMapper.touchReport(userId, dto.getVideoId());
        }

        // ===== 第 5 步：判定完课。只有"本次从 false 变 true"才发事件，防止重复发积分 =====
        boolean finishedNow = !alreadyFinished && newProgress >= 100;
        Integer points = null;
        if (finishedNow) {
            eventPublisher.publishCourseFinished(userId, video.getId(), video.getTitle());
            points = 30;
            log.info("用户完课: userId={}, videoId={}, 发放 {} 积分", userId, video.getId(), points);
        }
        return new VideoProgressResult(finishedNow || alreadyFinished, points);
    }

    private VideoVO toVO(Video v, Integer progress) {
        return VideoVO.builder()
                .id(v.getId())
                .title(v.getTitle())
                .coach(v.getCoach())
                .duration(v.getDuration())
                .level(v.getLevel())
                .calories(v.getCalories())
                .plays(v.getPlays())
                .progress(progress)
                .build();
    }
}
