package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.mapper.CoachMapper;
import com.yuedong.course.mapper.VideoMapper;
import com.yuedong.course.mapper.VideoProgressMapper;
import com.yuedong.course.mq.CourseEventPublisher;
import com.yuedong.course.model.dto.VideoProgressDTO;
import com.yuedong.course.model.entity.Coach;
import com.yuedong.course.model.entity.Video;
import com.yuedong.course.model.entity.VideoProgress;
import com.yuedong.course.model.vo.VideoProgressVO;
import com.yuedong.course.model.vo.VideoVO;
import com.yuedong.course.service.VideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 视频业务实现：进度只增不减；首次达到 100% 判定完课并发 MQ 事件（发积分）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoServiceImpl implements VideoService {

    /**
     * 首次上报允许的最大进度（%）：防止一上来就报 100% 完课。
     * <p>★不要设太大：首次上报后，后续允许进度按“总经过时间 + 视频时长”算，
     * 如果首报 20%、而 60 分钟视频在 10 秒时只允许约 15%，用户接下来会被误伤。</p>
     */
    private static final int FIRST_REPORT_MAX_PROGRESS = 10;

    private final VideoMapper videoMapper;
    private final VideoProgressMapper videoProgressMapper;
    private final CoachMapper coachMapper;
    private final CourseEventPublisher eventPublisher;

    @Override
    public List<VideoVO> list(Long userId) {
        // 只出上架视频，按课程内排序号排。★以前是 selectList(null)，把下架的也推给用户
        List<Video> videos = videoMapper.selectList(new LambdaQueryWrapper<Video>()
                .eq(Video::getStatus, 1)
                .orderByAsc(Video::getSortNo)
                .orderByAsc(Video::getId));
        // 已登录用户：一次性查出该用户全部进度，内存组装（避免 N+1）
        Map<Long, Integer> progressMap = userId == null
                ? Map.of()
                : videoProgressMapper.selectList(new LambdaQueryWrapper<VideoProgress>()
                        .eq(VideoProgress::getUserId, userId)).stream()
                        .collect(HashMap::new, (m, p) -> m.put(p.getVideoId(), p.getProgress()), HashMap::putAll);

        Map<Long, String> coachNameMap = coachNameMap(videos);
        return videos.stream()
                .map(v -> toVO(v, progressMap.get(v.getId()), coachNameMap.get(v.getCoachId())))
                .toList();
    }

    /**
     * 一次批量取这批视频用到的教练名。
     *
     * <p>★不在循环里 {@code selectById}：十几条视频就是十几次查询。t_video 只存 coach_id
     * 是有意的（同库可 JOIN，冗余名字等于给"改教练名"留一个永远漏更新的坑），
     * 所以名字在这里补。</p>
     */
    private Map<Long, String> coachNameMap(List<Video> videos) {
        List<Long> coachIds = videos.stream()
                .map(Video::getCoachId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (coachIds.isEmpty()) {
            return Map.of();
        }
        return coachMapper.selectList(new LambdaQueryWrapper<Coach>()
                        .select(Coach::getId, Coach::getName)
                        .in(Coach::getId, coachIds))
                .stream()
                .collect(Collectors.toMap(Coach::getId, Coach::getName, (a, b) -> a));
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
        String coachName = video.getCoachId() == null
                ? null
                : coachNameMap(List.of(video)).get(video.getCoachId());
        return toVO(video, progress, coachName);
    }

    @Override
    public VideoProgressVO reportProgress(Long userId, VideoProgressDTO dto) {
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
        // ★按"从首次上报到现在的总时长"算允许进度，而不是只看上一次上报：
        //   只看 last_report_at 时，每 10 秒报一点小增量可以绕过。
        // ★首次上报最多 20%：不能一上来就报 100% 完课。
        LocalDateTime now = LocalDateTime.now();
        int videoMinutes = video.getDuration() == null ? 0 : video.getDuration();
        int allowedProgress;
        if (record == null) {
            allowedProgress = FIRST_REPORT_MAX_PROGRESS;
        } else {
            LocalDateTime startAt = record.getFirstReportAt() == null
                    ? record.getLastReportAt() : record.getFirstReportAt();
            long elapsedSeconds = startAt == null ? 0 : Duration.between(startAt, now).toSeconds();
            if (videoMinutes > 0) {
                // 正常观看 N 分钟最多推进 100%；1.5 倍容差 + 15 个百分点给暂停/快进/退出重报
                int maxByDuration = (int) (elapsedSeconds * 100.0 / (videoMinutes * 60L) * 1.5) + 15;
                allowedProgress = Math.min(100, maxByDuration);
            } else {
                // 视频没配时长：退回按"每秒最多 1.5 个百分点"估算
                allowedProgress = Math.min(100, (int) (elapsedSeconds * 1.5) + 15);
            }
        }

        // 只在"进度真的往上走"时校验；心跳/兜底重报不涨进度，不能被旧进度误伤
        if (newProgress > oldProgress && newProgress > allowedProgress) {
            log.warn("进度增长异常，已拒绝: userId={}, videoId={}, old={}, new={}, allowed={}, duration={}min",
                    userId, dto.getVideoId(), oldProgress, newProgress, allowedProgress, videoMinutes);
            throw new BusinessException(ErrorCode.PARAM_ERROR, "进度异常，请正常观看视频");
        }

        // ===== 第 3 步：无记录则插入（UNIQUE(user_id,video_id) 兜底并发首报） =====
        if (record == null) {
            record = new VideoProgress();
            record.setUserId(userId);
            record.setVideoId(dto.getVideoId());
            record.setProgress(newProgress);
            record.setFinished(newProgress >= 100);
            // 首次上报时间与上次上报时间都从这里开始记；
            // 后续的防刷按 firstReportAt 到当前的总时长算。
            record.setFirstReportAt(now);
            record.setLastReportAt(now);
            record.setUpdatedAt(now);
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
            // 先发 MQ；发送成功才把 finish_event_sent 置 1。
            // 失败时保留 0，由 VideoFinishEventRetryTask 每分钟补偿重发。
            boolean sent = eventPublisher.publishCourseFinished(userId, video.getId(), video.getTitle());
            if (sent) {
                try {
                    videoProgressMapper.markFinishEventSent(userId, video.getId());
                } catch (Exception e) {
                    // 事件已经发出去了，标记失败只会导致补偿任务再发一次；
                    // 消费端按 userId:videoId 幂等，不会重复加积分。
                    log.error("完课事件已发送但标记失败，等待补偿任务重发: userId={}, videoId={}",
                            userId, video.getId(), e);
                }
                points = 30;
                log.info("用户完课: userId={}, videoId={}, 发放 {} 积分", userId, video.getId(), points);
            } else {
                log.warn("完课事件发送失败，已留给补偿任务: userId={}, videoId={}",
                        userId, video.getId());
            }
        }
        return new VideoProgressVO(finishedNow || alreadyFinished, points);
    }

    private VideoVO toVO(Video v, Integer progress, String coachName) {
        return VideoVO.builder()
                .id(v.getId())
                .title(v.getTitle())
                .intro(v.getIntro())
                // ★名字由调用方批量查出后传进来（见 coachNameMap），实体里只有 coach_id
                .coach(coachName)
                .coachId(v.getCoachId())
                .courseId(v.getCourseId())
                .duration(v.getDuration())
                .level(v.getLevel())
                .calories(v.getCalories())
                .plays(v.getPlays())
                // url 与封面必须透出：少了 url，前端播放器只能永远是假的
                .url(v.getUrl())
                .coverImage(v.getCoverImage())
                .progress(progress)
                .build();
    }
}
