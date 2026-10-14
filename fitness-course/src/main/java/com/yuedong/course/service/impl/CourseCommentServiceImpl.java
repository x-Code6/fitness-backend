package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.course.mapper.CourseCommentMapper;
import com.yuedong.course.mapper.CourseMapper;
import com.yuedong.course.model.dto.CourseCommentDTO;
import com.yuedong.course.model.entity.Course;
import com.yuedong.course.model.entity.CourseComment;
import com.yuedong.course.model.vo.CourseCommentVO;
import com.yuedong.course.service.CourseCommentService;
import com.yuedong.user.api.client.UserClient;
import com.yuedong.user.api.dto.BatchUserQueryDTO;
import com.yuedong.user.api.dto.UserBriefDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 展示课评论实现。
 *
 * <p>★两级结构在这里的读法是<b>两次查询</b>而不是自连接或递归：
 * 一次取本页一级（按 id 倒序、有上限），再一次用 {@code root_id IN (...)} 把这些一级的回复全捞回来，
 * 在内存里按 rootId 分组贴上去。总共两条 SQL，都走索引。</p>
 *
 * <p>★和评论有关的所有跨库取数（昵称、头像）只在<b>写入时</b>做一次快照，
 * 读列表时一次都不回查用户服务——一页 200 条评论发一次 Feign 看似省，
 * 但改名之后历史评论会跟着变，而"我当时看到的不是这句"在内容页上是会被截图挂出来的问题。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseCommentServiceImpl implements CourseCommentService {

    /** 一级评论最多取多少条。★一期不做翻页，但必须有上限（详见接口注释） */
    private static final int MAX_ROOTS = 200;

    /** 展示课的类型位（D11）。可售课的"用户说的话"是评价，不是评论 */
    private static final String KIND_CONTENT = "CONTENT";

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final CourseCommentMapper commentMapper;
    private final CourseMapper courseMapper;
    private final UserClient userClient;

    @Override
    public List<CourseCommentVO> list(Long courseId, Long userId) {
        // ===== 第 1 步：只有展示型课程能评论（★这一句是整个 D11 的闸门）=====
        loadContentCourse(courseId);

        // ===== 第 2 步：本页一级（id 倒序 = 时间倒序）=====
        List<CourseComment> roots = commentMapper.selectList(new LambdaQueryWrapper<CourseComment>()
                .eq(CourseComment::getCourseId, courseId)
                .eq(CourseComment::getRootId, 0L)
                .eq(CourseComment::getStatus, 1)
                .orderByDesc(CourseComment::getId)
                .last("LIMIT " + MAX_ROOTS));
        if (roots.isEmpty()) {
            return List.of();
        }

        // ===== 第 3 步：这些一级的回复，一次捞完再分组 =====
        // ★不在循环里逐条查回复：一级有 200 条就是 200 次查询（N+1）
        List<Long> rootIds = roots.stream().map(CourseComment::getId).toList();
        List<CourseComment> replies = commentMapper.selectList(new LambdaQueryWrapper<CourseComment>()
                .in(CourseComment::getRootId, rootIds)
                .eq(CourseComment::getStatus, 1)
                .orderByAsc(CourseComment::getId));      // 回复按时间升序，越早越前，符合对话阅读顺序

        Map<Long, List<CourseComment>> grouped = new HashMap<>();
        for (CourseComment reply : replies) {
            grouped.computeIfAbsent(reply.getRootId(), k -> new ArrayList<>()).add(reply);
        }

        List<CourseCommentVO> list = new ArrayList<>();
        for (CourseComment root : roots) {
            List<CourseCommentVO> replyVOs = grouped.getOrDefault(root.getId(), List.of())
                    .stream().map(reply -> toVO(reply, userId, List.of())).toList();
            list.add(toVO(root, userId, replyVOs));
        }
        return list;
    }

    @Override
    public CourseCommentVO add(Long userId, CourseCommentDTO dto) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // ===== 第 1 步：课程必须是展示课 =====
        loadContentCourse(dto.getCourseId());

        // ===== 第 2 步：回复时校验父评论，并从它身上取两个派生值 =====
        CourseComment parent = null;
        long rootId = 0L;
        if (dto.getParentId() != null) {
            parent = commentMapper.selectById(dto.getParentId());
            // ★必须判"父评论属于同一门课"：只判存在，就能把回复挂到别的课的评论下面
            if (parent == null || parent.getStatus() != 1
                    || !parent.getCourseId().equals(dto.getCourseId())) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "回复的评论不存在或已被删除");
            }
            // 回复一级 → root 就是它自己（它的 root_id=0）；回复二级 → 继承 parent 的 root_id。
            // 这样"两级"是靠 root_id 拍的平铺结构，不需要递归查 parent 链
            rootId = parent.getRootId() == null || parent.getRootId() == 0L
                    ? parent.getId() : parent.getRootId();
        }

        // ===== 第 3 步：昵称/头像快照（Feign 一次），落库 =====
        UserBriefDTO user = fetchUser(userId);
        CourseComment comment = new CourseComment();
        comment.setCourseId(dto.getCourseId());
        comment.setRootId(rootId);
        comment.setParentId(dto.getParentId());
        comment.setUserId(userId);
        comment.setUserName(user != null && user.getNickname() != null ? user.getNickname() : "悦动同学");
        comment.setAvatarColor(user != null && user.getAvatarColor() != null ? user.getAvatarColor() : "0");
        comment.setContent(dto.getContent().trim());
        // ★被回复人昵称取<b>父评论那一行的快照</b>，绝不取前端传的值：
        //   前端能传任意字符串，等于允许在课程页伪造"回复 @教练"
        comment.setReplyToUser(parent == null ? null : parent.getUserName());
        comment.setStatus(1);
        comment.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(comment);

        return toVO(comment, userId, List.of());
    }

    @Override
    public void delete(Long userId, Long commentId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        CourseComment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getStatus() != 1) {
            throw new BusinessException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // ★只按"是不是本人"判，不看角色：评论是用户内容，运营不该替用户删（那是审计问题不是权限问题）
        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己的评论");
        }
        CourseComment update = new CourseComment();
        update.setId(commentId);
        update.setStatus(0);
        commentMapper.updateById(update);
        // ⚠️ 一期不做"删一级时级联标记它的回复"：列表读的是 status=1，
        //   被删一级的回复会跟着从页面上消失（它们只挂在这个一级下面渲染）。
        //   留下的孤儿行不影响正确性，只是不被展示——真要清是运维脚本的活，不该塞进业务方法里
    }

    @Override
    public int countByCourse(Long courseId) {
        Long count = commentMapper.selectCount(new LambdaQueryWrapper<CourseComment>()
                .eq(CourseComment::getCourseId, courseId)
                .eq(CourseComment::getStatus, 1));
        return count == null ? 0 : count.intValue();
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 课程必须存在、未下架，且是展示型（CONTENT） */
    private Course loadContentCourse(Long courseId) {
        Course course = courseMapper.selectById(courseId);
        if (course == null || course.getStatus() == null || course.getStatus() != 1) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        if (!KIND_CONTENT.equals(course.getCourseKind())) {
            // ★专门给一句人话而不是笼统 PARAM_ERROR：用户在可售课详情页看到"发表评论"按钮时，
            //   说明前端把两类课搞混了，这句报错是唯一的线索
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该课程不是展示型课程，请到订单里评价");
        }
        return course;
    }

    /** Feign 取用户简况；失败降级默认昵称，不发评论失败 */
    private UserBriefDTO fetchUser(Long userId) {
        try {
            Result<List<UserBriefDTO>> result = userClient.batch(new BatchUserQueryDTO(List.of(userId)));
            if (result.getCode() == 0 && result.getData() != null && !result.getData().isEmpty()) {
                return result.getData().get(0);
            }
        } catch (Exception e) {
            log.warn("评论取用户信息失败，用默认昵称: userId={}", userId, e);
        }
        UserBriefDTO fallback = new UserBriefDTO();
        fallback.setId(userId);
        fallback.setNickname("悦动同学");
        fallback.setAvatarColor("0");
        return fallback;
    }

    private CourseCommentVO toVO(CourseComment comment, Long userId, List<CourseCommentVO> replies) {
        Integer avatarIdx = 0;
        try {
            avatarIdx = comment.getAvatarColor() == null ? 0 : Integer.parseInt(comment.getAvatarColor().trim());
        } catch (NumberFormatException e) {
            // 脏的配色下标按 0 号显示，不让一条坏数据把整页评论打挂
            log.warn("评论头像配色是脏值: id={}, value={}", comment.getId(), comment.getAvatarColor());
        }
        return CourseCommentVO.builder()
                .commentId(comment.getId())
                .userId(comment.getUserId())
                .userName(comment.getUserName())
                .avatarColor(avatarIdx)
                .content(comment.getContent())
                .createdText(comment.getCreatedAt() == null ? "" : comment.getCreatedAt().format(TIME_FMT))
                .agoText(agoText(comment.getCreatedAt()))
                .replyToUser(comment.getReplyToUser())
                .mine(userId != null && comment.getUserId().equals(userId))
                .replies(replies)
                .build();
    }

    /** 相对时间文案（与社区评论同一套口径） */
    private String agoText(LocalDateTime time) {
        if (time == null) {
            return "刚刚";
        }
        Duration d = Duration.between(time, LocalDateTime.now());
        if (d.toMinutes() < 1) return "刚刚";
        if (d.toHours() < 1) return d.toMinutes() + " 分钟前";
        if (d.toHours() < 24) return d.toHours() + " 小时前";
        if (d.toDays() == 1) return "昨天 " + time.format(DateTimeFormatter.ofPattern("HH:mm"));
        return time.format(DateTimeFormatter.ofPattern("MM-dd"));
    }
}
