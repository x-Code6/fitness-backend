package com.yuedong.course.task;

import com.yuedong.course.mapper.VideoMapper;
import com.yuedong.course.mapper.VideoProgressMapper;
import com.yuedong.course.model.entity.Video;
import com.yuedong.course.model.entity.VideoProgress;
import com.yuedong.course.mq.CourseEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 完课事件补偿任务：扫描"进度已完成、但事件没发出去"的记录并重发。
 *
 * <p>★为什么需要它：{@link com.yuedong.course.service.impl.VideoServiceImpl} 是
 * "先把进度置 finished=1，再发 MQ"。如果 MQ 正好不可用，事件就丢了；
 * 而 finished 已经是 1，用户之后再上报进度也不会再发。结果就是视频看完了、30 积分永远不到账。</p>
 *
 * <p>★为什么重发不会重复加分：事件 id 是业务键 {@code userId:videoId}，
 * point 服务的 {@code CourseFinishedConsumer} 有 Redis SETNX + 数据库 uk_biz 两层幂等。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoFinishEventRetryTask {

    /** 一轮最多处理多少条：失败的消息不会把整个任务卡死 */
    private static final int BATCH_SIZE = 20;

    private final VideoProgressMapper videoProgressMapper;
    private final VideoMapper videoMapper;
    private final CourseEventPublisher eventPublisher;

    /** 每分钟一次；fixedDelay 保证上一轮跑完再计时，不会两轮叠在一起 */
    @Scheduled(fixedDelayString = "${yd.video.finish-event-retry-ms:60000}")
    public void retry() {
        List<VideoProgress> rows = videoProgressMapper.selectUnsentFinished(BATCH_SIZE);
        if (rows.isEmpty()) {
            return;
        }

        for (VideoProgress row : rows) {
            try {
                // 视频可能已被删除；标题给空串而不是让事件发不出去。
                // 积分该不该发由"用户确实看完了"决定，不依赖视频是否还在架上。
                Video video = videoMapper.selectById(row.getVideoId());
                String title = video == null ? "" : video.getTitle();

                boolean sent = eventPublisher.publishCourseFinished(
                        row.getUserId(), row.getVideoId(), title);
                if (sent) {
                    videoProgressMapper.markFinishEventSent(row.getUserId(), row.getVideoId());
                    log.info("完课事件补偿发送成功: userId={}, videoId={}",
                            row.getUserId(), row.getVideoId());
                } else {
                    log.warn("完课事件补偿仍失败，下一轮继续: userId={}, videoId={}",
                            row.getUserId(), row.getVideoId());
                }
            } catch (Exception e) {
                // 单条失败不影响这一轮的其他记录
                log.error("完课事件补偿异常，下一轮继续: userId={}, videoId={}",
                        row.getUserId(), row.getVideoId(), e);
            }
        }
    }
}
