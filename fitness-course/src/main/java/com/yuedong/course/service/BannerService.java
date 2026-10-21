package com.yuedong.course.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.course.api.dto.BannerAdminDTO;
import com.yuedong.course.api.dto.BannerDTO;
import com.yuedong.course.api.dto.BannerSaveDTO;
import com.yuedong.course.api.dto.CouponAdminDTO;
import com.yuedong.course.api.dto.CouponSaveDTO;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.SeckillAdminDTO;
import com.yuedong.course.api.dto.SeckillSaveDTO;

import java.util.List;

/**
 * 首页活动轮播：来源校验、上下架、与课程/券/秒杀保存联动。
 */
public interface BannerService {

    /** 用户端：只返回当前有效且来源仍有效的轮播 */
    List<BannerDTO> listActive();

    PageDTO<BannerAdminDTO> adminPage(Integer page, Integer size);

    void adminSave(BannerSaveDTO dto);

    void setStatus(Long id, Integer status);

    /** 课程保存后同步 Banner；bannerEnabled=false 时下架已有 Banner */
    void upsertForCourse(Long courseId, CourseSaveDTO dto);

    void upsertForCoupon(Long couponId, CouponSaveDTO dto);

    void upsertForSeckill(Long activityId, SeckillSaveDTO dto);

    /** 关联来源下架/删除时隐藏 Banner，避免首页出现死链接 */
    void disableByCourse(Long courseId);

    void disableByCoupon(Long couponId);

    void disableBySeckill(Long activityId);

    /** 管理端编辑回填 */
    void fillCourseBanners(List<CourseAdminDTO> rows);

    void fillCouponBanners(List<CouponAdminDTO> rows);

    void fillSeckillBanners(List<SeckillAdminDTO> rows);
}
