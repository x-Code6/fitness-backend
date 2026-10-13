package com.yuedong.community.service;

import com.yuedong.community.model.dto.CommentDTO;
import com.yuedong.community.model.dto.PublishDTO;
import com.yuedong.community.model.vo.CommentVO;
import com.yuedong.community.model.vo.LikeResultVO;
import com.yuedong.community.model.vo.PostVO;

import java.util.List;

/**
 * 社区业务接口。
 */
public interface CommunityService {

    /**
     * Feed 流分页。
     *
     * @param page   页码（1 起）
     * @param userId 当前登录用户（可为 null：匿名浏览，liked/mine 恒 false）
     * @param tab    all 全部动态（DB 分页）/ follow 关注的人的动态（推模式收件箱）
     * @return {list, hasMore, page}，结构对齐前端
     */
    com.yuedong.community.model.vo.FeedResultVO feed(int page, Long userId, String tab);

    /** 发布动态（昵称经 Feign 快照） */
    PostVO publish(Long userId, PublishDTO dto);

    /** 点赞/取消点赞（幂等切换） */
    LikeResultVO like(Long userId, Long postId);

    /** 动态的评论列表（两级：一级评论携带 replies） */
    List<CommentVO> comments(Long postId);

    /** 发表评论/回复 */
    CommentVO addComment(Long userId, CommentDTO dto);
}
