package com.yuedong.course.service;

import com.yuedong.course.model.dto.CourseCommentDTO;
import com.yuedong.course.model.vo.CourseCommentVO;

import java.util.List;

/**
 * 展示型课程评论业务（D11 的 CONTENT 课专属）。
 *
 * <p>★<b>只有 CONTENT 课可以评论</b>，这一条判定在实现里每次都会做：
 * 可售课的"用户说的话"是<b>评价</b>（要买过、带星级、进评分统计，在 yd_order），
 * 如果两种课都能评论，同一个人就会有两个说话的地方，
 * 而运营看口碑时两边都要查、还查不全。</p>
 */
public interface CourseCommentService {

    /**
     * 某门课的评论列表：一级按时间倒序，每条一级带它自己的全部回复（回复按时间升序）。
     *
     * <p>★不做分页、但<b>有上限</b>（实现里的 MAX_ROOTS）：一期展示课是运营自己放的跟练内容，
     * 评论量是个位数到几十条；上限只是防止"某门课爆了一次"时一次拖出几万行，
     * 而不是为了做一个真正的翻页组件。</p>
     *
     * @param userId 当前登录用户，用来标 mine（决定删除按钮）
     */
    List<CourseCommentVO> list(Long courseId, Long userId);

    /** 发表评论/回复。昵称与头像走用户服务快照 */
    CourseCommentVO add(Long userId, CourseCommentDTO dto);

    /** 删除自己发的评论（软删） */
    void delete(Long userId, Long commentId);

    /** 某门课的正常评论数（详情页头部"共 N 条评价"用） */
    int countByCourse(Long courseId);
}
