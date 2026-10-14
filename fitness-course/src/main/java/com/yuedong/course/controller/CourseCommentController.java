package com.yuedong.course.controller;

import com.yuedong.common.result.Result;
import com.yuedong.common.util.UserContext;
import com.yuedong.course.model.dto.CourseCommentDTO;
import com.yuedong.course.model.vo.CourseCommentVO;
import com.yuedong.course.service.CourseCommentService;
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
 * 展示型课程评论接口（D11）。
 *
 * <p>网关路由：{@code /api/course/**} → 本服务 {@code /course/**}，所以这三条口
 * 外部路径是 {@code /api/course/comment}。</p>
 *
 * <p>★<b>读也要登录</b>（这三条都没有进网关白名单，包括 GET）。
 * 理由不是"防止别人看评论"，而是两条更实际的：
 * ① 返回里有 {@code mine}，未登录时整页都是 false，前端要再处理一种状态；
 * ② 评论列表是"批量扒内容"最省事的入口，白名单里少一条就少一个口子。
 * 展示课详情页对游客开放，但游客看到的是"登录后可评论"，这是产品决定而不是技术限制。</p>
 */
@RestController
@RequestMapping("/course/comment")
@RequiredArgsConstructor
public class CourseCommentController {

    private final CourseCommentService courseCommentService;

    /** 某门展示课的评论列表（一级倒序，每条一级带它自己的全部回复） */
    @GetMapping
    public Result<List<CourseCommentVO>> list(@RequestParam Long courseId) {
        return Result.ok(courseCommentService.list(courseId, UserContext.getUserId()));
    }

    /** 发表评论/回复（parentId 传了就是回复；被回复人昵称服务端取，不接受前端传） */
    @PostMapping
    public Result<CourseCommentVO> add(@Valid @RequestBody CourseCommentDTO dto) {
        return Result.ok(courseCommentService.add(UserContext.getUserId(), dto));
    }

    /** 删除自己的评论（软删） */
    @PostMapping("/delete")
    public Result<Void> delete(@RequestParam Long commentId) {
        courseCommentService.delete(UserContext.getUserId(), commentId);
        return Result.ok();
    }
}
