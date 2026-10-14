package com.yuedong.course.controller;

import com.yuedong.common.result.Result;
import com.yuedong.course.model.vo.CourseVO;
import com.yuedong.course.service.CourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程接口（C 端）。
 *
 * <p>网关路由：{@code /api/course/**} → 本服务 {@code /course/**}（StripPrefix=1）。
 * {@code list} 与 {@code detail} 在免登录白名单内（游客可浏览），其余需要登录。</p>
 *
 * <p>★v2 删掉了 {@code GET /course/schedules}：场次表整体废弃。
 * "什么时候能上课"改由<b>教练空闲时段</b>回答，接口在 order 域
 * （{@code /api/reservation/slots}），因为占用数据 {@code t_coach_busy} 在那个库。
 * 放在课程侧反而要跨库取数，位置由数据归属决定，不由"看起来像课程功能"决定。</p>
 */
@RestController
@RequestMapping("/course")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    /**
     * 课程列表：分类筛选 + 关键词搜索 + 形态筛选。
     *
     * @param type    分类（燃脂/力量/…），"全部"或空表示不过滤
     * @param keyword 匹配课程名 / 标签 / 分类
     * @param kind    SALE 可购买课 / CONTENT 展示课；空表示两类都要
     */
    @GetMapping("/list")
    public Result<List<CourseVO>> list(@RequestParam(required = false) String type,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String kind) {
        return Result.ok(courseService.list(type, keyword, kind));
    }

    /**
     * 课程详情（缓存三件套：空值缓存防穿透 / 逻辑过期 + 互斥异步重建防击穿 / 物理 TTL + 抖动防雪崩）。
     *
     * @param id      课程 id
     * @param storeId 可选门店上下文。传了才计算"本店几位教练可约"——
     *                这个值与门店绑定，<b>不进缓存</b>，每次走一次索引 COUNT
     */
    @GetMapping("/detail")
    public Result<CourseVO> detail(@RequestParam Long id,
                                   @RequestParam(required = false) Long storeId) {
        return Result.ok(courseService.getDetail(id, storeId));
    }
}
