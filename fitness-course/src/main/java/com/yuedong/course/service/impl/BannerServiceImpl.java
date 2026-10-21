package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.model.PageDTO;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.api.dto.BannerAdminDTO;
import com.yuedong.course.api.dto.BannerDTO;
import com.yuedong.course.api.dto.BannerSaveDTO;
import com.yuedong.course.api.dto.CouponAdminDTO;
import com.yuedong.course.api.dto.CouponSaveDTO;
import com.yuedong.course.api.dto.CourseAdminDTO;
import com.yuedong.course.api.dto.CourseSaveDTO;
import com.yuedong.course.api.dto.SeckillAdminDTO;
import com.yuedong.course.api.dto.SeckillSaveDTO;
import com.yuedong.course.mapper.BannerMapper;
import com.yuedong.course.mapper.CouponMapper;
import com.yuedong.course.mapper.CourseMapper;
import com.yuedong.course.mapper.SeckillActivityMapper;
import com.yuedong.course.model.entity.Banner;
import com.yuedong.course.model.entity.Coupon;
import com.yuedong.course.model.entity.Course;
import com.yuedong.course.model.entity.SeckillActivity;
import com.yuedong.course.service.BannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 活动轮播实现。
 *
 * <p>核心原则：<b>Banner 不是独立真相</b>。来源课程下架、秒杀结束、券截止后，
 * 即使管理员忘了下架 Banner，查询也必须把它过滤掉，否则首页会出现死链接。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BannerServiceImpl implements BannerService {

    private static final String TYPE_COURSE = "COURSE";
    private static final String TYPE_SECKILL = "SECKILL";
    private static final String TYPE_COUPON = "COUPON";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BannerMapper bannerMapper;
    private final CourseMapper courseMapper;
    private final SeckillActivityMapper seckillActivityMapper;
    private final CouponMapper couponMapper;

    // =====================================================================
    // 用户端
    // =====================================================================
    @Override
    public List<BannerDTO> listActive() {
        LocalDateTime now = LocalDateTime.now();
        List<Banner> banners = bannerMapper.selectList(new LambdaQueryWrapper<Banner>()
                .eq(Banner::getStatus, 1)
                .and(w -> w.isNull(Banner::getStartTime).or().le(Banner::getStartTime, now))
                .and(w -> w.isNull(Banner::getEndTime).or().gt(Banner::getEndTime, now))
                .orderByAsc(Banner::getSortNo)
                .orderByDesc(Banner::getId));
        if (banners.isEmpty()) {
            return List.of();
        }

        // 一次性把三种来源批量查回来，避免每条 Banner 查一次
        Set<Long> courseIds = new HashSet<>();
        Set<Long> activityIds = new HashSet<>();
        Set<Long> couponIds = new HashSet<>();
        for (Banner banner : banners) {
            if (TYPE_COURSE.equals(banner.getLinkType())) {
                courseIds.add(banner.getTargetId());
            } else if (TYPE_SECKILL.equals(banner.getLinkType())) {
                activityIds.add(banner.getTargetId());
            } else if (TYPE_COUPON.equals(banner.getLinkType())) {
                couponIds.add(banner.getTargetId());
            }
        }

        Map<Long, SeckillActivity> activityMap = new HashMap<>();
        if (!activityIds.isEmpty()) {
            for (SeckillActivity activity : seckillActivityMapper.selectBatchIds(activityIds)) {
                activityMap.put(activity.getId(), activity);
                if (activity.getCourseId() != null) {
                    courseIds.add(activity.getCourseId());
                }
            }
        }
        Map<Long, Course> courseMap = new HashMap<>();
        if (!courseIds.isEmpty()) {
            for (Course course : courseMapper.selectBatchIds(courseIds)) {
                courseMap.put(course.getId(), course);
            }
        }
        Map<Long, Coupon> couponMap = new HashMap<>();
        if (!couponIds.isEmpty()) {
            for (Coupon coupon : couponMapper.selectBatchIds(couponIds)) {
                couponMap.put(coupon.getId(), coupon);
            }
        }

        List<BannerDTO> result = new ArrayList<>();
        for (Banner banner : banners) {
            BannerDTO dto = toPublicDTO(banner, now, courseMap, activityMap, couponMap);
            if (dto != null) {
                result.add(dto);
            }
        }
        return result;
    }

    private BannerDTO toPublicDTO(Banner banner, LocalDateTime now,
                                  Map<Long, Course> courseMap,
                                  Map<Long, SeckillActivity> activityMap,
                                  Map<Long, Coupon> couponMap) {
        BannerDTO.BannerDTOBuilder builder = BannerDTO.builder()
                .id(banner.getId())
                .title(banner.getTitle())
                .subtitle(banner.getSubtitle())
                .imageUrl(banner.getImageUrl())
                .linkType(banner.getLinkType())
                .sortNo(banner.getSortNo());

        if (TYPE_COURSE.equals(banner.getLinkType())) {
            Course course = courseMap.get(banner.getTargetId());
            if (course == null || !Integer.valueOf(1).equals(course.getStatus())) {
                return null;
            }
            return builder.courseId(course.getId()).build();
        }
        if (TYPE_SECKILL.equals(banner.getLinkType())) {
            SeckillActivity activity = activityMap.get(banner.getTargetId());
            if (activity == null || !Integer.valueOf(1).equals(activity.getStatus())
                    || activity.getEndTime() == null || !activity.getEndTime().isAfter(now)) {
                return null;
            }
            Course course = courseMap.get(activity.getCourseId());
            if (course == null || !Integer.valueOf(1).equals(course.getStatus())) {
                return null;
            }
            return builder.courseId(activity.getCourseId()).activityId(activity.getId()).build();
        }
        if (TYPE_COUPON.equals(banner.getLinkType())) {
            Coupon coupon = couponMap.get(banner.getTargetId());
            if (coupon == null || !Integer.valueOf(1).equals(coupon.getStatus())) {
                return null;
            }
            if (coupon.getReceiveStartTime() != null && coupon.getReceiveStartTime().isAfter(now)) {
                return null;
            }
            if (coupon.getReceiveEndTime() != null && !coupon.getReceiveEndTime().isAfter(now)) {
                return null;
            }
            return builder.couponId(coupon.getId()).build();
        }
        return null;
    }

    // =====================================================================
    // 管理端
    // =====================================================================
    @Override
    public PageDTO<BannerAdminDTO> adminPage(Integer page, Integer size) {
        Page<Banner> pageResult = bannerMapper.selectPage(
                new Page<>(page == null || page < 1 ? 1 : page,
                        size == null || size < 1 ? 10 : Math.min(size, 50)),
                new LambdaQueryWrapper<Banner>()
                        .orderByAsc(Banner::getSortNo)
                        .orderByDesc(Banner::getId));

        List<Banner> records = pageResult.getRecords();
        if (records.isEmpty()) {
            return PageDTO.of(List.of(), pageResult.getTotal());
        }

        Set<Long> courseIds = new HashSet<>();
        Set<Long> activityIds = new HashSet<>();
        Set<Long> couponIds = new HashSet<>();
        for (Banner banner : records) {
            if (TYPE_COURSE.equals(banner.getLinkType())) {
                courseIds.add(banner.getTargetId());
            } else if (TYPE_SECKILL.equals(banner.getLinkType())) {
                activityIds.add(banner.getTargetId());
            } else if (TYPE_COUPON.equals(banner.getLinkType())) {
                couponIds.add(banner.getTargetId());
            }
        }
        Map<Long, SeckillActivity> activityMap = new HashMap<>();
        if (!activityIds.isEmpty()) {
            for (SeckillActivity activity : seckillActivityMapper.selectBatchIds(activityIds)) {
                activityMap.put(activity.getId(), activity);
                if (activity.getCourseId() != null) {
                    courseIds.add(activity.getCourseId());
                }
            }
        }
        Map<Long, Course> courseMap = new HashMap<>();
        if (!courseIds.isEmpty()) {
            for (Course course : courseMapper.selectBatchIds(courseIds)) {
                courseMap.put(course.getId(), course);
            }
        }
        Map<Long, Coupon> couponMap = new HashMap<>();
        if (!couponIds.isEmpty()) {
            for (Coupon coupon : couponMapper.selectBatchIds(couponIds)) {
                couponMap.put(coupon.getId(), coupon);
            }
        }

        List<BannerAdminDTO> rows = new ArrayList<>(records.size());
        for (Banner banner : records) {
            BannerAdminDTO.BannerAdminDTOBuilder builder = BannerAdminDTO.builder()
                    .id(banner.getId())
                    .title(banner.getTitle())
                    .subtitle(banner.getSubtitle())
                    .imageUrl(banner.getImageUrl())
                    .linkType(banner.getLinkType())
                    .targetId(banner.getTargetId())
                    .sortNo(banner.getSortNo())
                    .status(banner.getStatus())
                    .startTime(banner.getStartTime())
                    .endTime(banner.getEndTime())
                    .createdAt(banner.getCreatedAt());

            if (TYPE_COURSE.equals(banner.getLinkType())) {
                Course course = courseMap.get(banner.getTargetId());
                builder.courseId(banner.getTargetId())
                        .sourceName(course == null ? "课程已删除" : course.getName());
            } else if (TYPE_SECKILL.equals(banner.getLinkType())) {
                SeckillActivity activity = activityMap.get(banner.getTargetId());
                Course course = activity == null ? null : courseMap.get(activity.getCourseId());
                builder.activityId(banner.getTargetId())
                        .courseId(activity == null ? null : activity.getCourseId())
                        .sourceName(course == null ? "秒杀活动已删除" : course.getName() + " · 秒杀");
            } else if (TYPE_COUPON.equals(banner.getLinkType())) {
                Coupon coupon = couponMap.get(banner.getTargetId());
                builder.couponId(banner.getTargetId())
                        .sourceName(coupon == null ? "优惠券已删除" : coupon.getTitle());
            }
            rows.add(builder.build());
        }
        return PageDTO.of(rows, pageResult.getTotal());
    }

    @Override
    public void adminSave(BannerSaveDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "活动页 id 不能为空");
        }
        Banner banner = bannerMapper.selectById(dto.getId());
        if (banner == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "活动页不存在");
        }
        banner.setTitle(dto.getTitle());
        banner.setSubtitle(dto.getSubtitle());
        banner.setSortNo(dto.getSortNo() == null ? 0 : dto.getSortNo());
        if (dto.getStatus() != null) {
            banner.setStatus(dto.getStatus());
        }
        if (dto.getStartTime() != null && !dto.getStartTime().isBlank()) {
            banner.setStartTime(parseTime(dto.getStartTime()));
        }
        if (dto.getEndTime() != null && !dto.getEndTime().isBlank()) {
            banner.setEndTime(parseTime(dto.getEndTime()));
        }
        banner.setUpdatedAt(LocalDateTime.now());
        bannerMapper.updateById(banner);
    }

    @Override
    public void setStatus(Long id, Integer status) {
        Banner banner = bannerMapper.selectById(id);
        if (banner == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "活动页不存在");
        }
        banner.setStatus(Integer.valueOf(1).equals(status) ? 1 : 0);
        banner.setUpdatedAt(LocalDateTime.now());
        bannerMapper.updateById(banner);
    }

    // =====================================================================
    // 来源保存联动
    // =====================================================================
    @Override
    public void upsertForCourse(Long courseId, CourseSaveDTO dto) {
        LocalDateTime start = parseTime(dto.getBannerStartTime());
        LocalDateTime end = parseTime(dto.getBannerEndTime());
        upsert(TYPE_COURSE, courseId, Boolean.TRUE.equals(dto.getBannerEnabled()),
                dto.getBannerImageUrl(), dto.getBannerTitle(), dto.getBannerSubtitle(),
                dto.getBannerSort(), start == null ? LocalDateTime.now() : start, end);
    }

    @Override
    public void upsertForCoupon(Long couponId, CouponSaveDTO dto) {
        LocalDateTime start = parseTime(dto.getBannerStartTime());
        LocalDateTime end = parseTime(dto.getBannerEndTime());
        if (start == null) {
            start = parseTime(dto.getReceiveStartTime());
        }
        if (end == null) {
            end = parseTime(dto.getReceiveEndTime());
        }
        upsert(TYPE_COUPON, couponId, Boolean.TRUE.equals(dto.getBannerEnabled()),
                dto.getBannerImageUrl(), dto.getBannerTitle(), dto.getBannerSubtitle(),
                dto.getBannerSort(), start, end);
    }

    @Override
    public void upsertForSeckill(Long activityId, SeckillSaveDTO dto) {
        LocalDateTime start = parseTime(dto.getBannerStartTime());
        LocalDateTime end = parseTime(dto.getBannerEndTime());
        if (start == null) {
            start = parseTime(dto.getStartTime());
        }
        if (end == null) {
            end = parseTime(dto.getEndTime());
        }
        upsert(TYPE_SECKILL, activityId, Boolean.TRUE.equals(dto.getBannerEnabled()),
                dto.getBannerImageUrl(), dto.getBannerTitle(), dto.getBannerSubtitle(),
                dto.getBannerSort(), start, end);
    }

    private void upsert(String linkType, Long targetId, boolean enabled,
                        String imageUrl, String title, String subtitle, Integer sortNo,
                        LocalDateTime startTime, LocalDateTime endTime) {
        Banner existing = bannerMapper.selectOne(new LambdaQueryWrapper<Banner>()
                .eq(Banner::getLinkType, linkType)
                .eq(Banner::getTargetId, targetId));

        if (!enabled) {
            if (existing != null && !Integer.valueOf(0).equals(existing.getStatus())) {
                existing.setStatus(0);
                existing.setUpdatedAt(LocalDateTime.now());
                bannerMapper.updateById(existing);
            }
            return;
        }
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "上架活动页必须上传海报");
        }

        Banner banner = existing == null ? new Banner() : existing;
        banner.setLinkType(linkType);
        banner.setTargetId(targetId);
        banner.setTitle(title);
        banner.setSubtitle(subtitle);
        banner.setImageUrl(imageUrl);
        banner.setSortNo(sortNo == null ? 0 : sortNo);
        banner.setStatus(1);
        banner.setStartTime(startTime);
        banner.setEndTime(endTime);
        banner.setUpdatedAt(LocalDateTime.now());
        if (existing == null) {
            banner.setCreatedAt(LocalDateTime.now());
            bannerMapper.insert(banner);
        } else {
            bannerMapper.updateById(banner);
        }
    }

    @Override
    public void disableByCourse(Long courseId) {
        for (Banner banner : bannerMapper.selectList(new LambdaQueryWrapper<Banner>()
                .eq(Banner::getLinkType, TYPE_COURSE)
                .eq(Banner::getTargetId, courseId))) {
            banner.setStatus(0);
            banner.setUpdatedAt(LocalDateTime.now());
            bannerMapper.updateById(banner);
        }
        // 该课程的秒杀活动也一并隐藏
        for (SeckillActivity activity : seckillActivityMapper.selectList(
                new LambdaQueryWrapper<SeckillActivity>().eq(SeckillActivity::getCourseId, courseId))) {
            disableBySeckill(activity.getId());
        }
    }

    @Override
    public void disableByCoupon(Long couponId) {
        for (Banner banner : bannerMapper.selectList(new LambdaQueryWrapper<Banner>()
                .eq(Banner::getLinkType, TYPE_COUPON)
                .eq(Banner::getTargetId, couponId))) {
            banner.setStatus(0);
            banner.setUpdatedAt(LocalDateTime.now());
            bannerMapper.updateById(banner);
        }
    }

    @Override
    public void disableBySeckill(Long activityId) {
        for (Banner banner : bannerMapper.selectList(new LambdaQueryWrapper<Banner>()
                .eq(Banner::getLinkType, TYPE_SECKILL)
                .eq(Banner::getTargetId, activityId))) {
            banner.setStatus(0);
            banner.setUpdatedAt(LocalDateTime.now());
            bannerMapper.updateById(banner);
        }
    }

    // =====================================================================
    // 管理端回填
    // =====================================================================
    @Override
    public void fillCourseBanners(List<CourseAdminDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(CourseAdminDTO::getId).toList();
        Map<Long, Banner> map = mapBySource(TYPE_COURSE, ids);
        for (CourseAdminDTO row : rows) {
            fill(row, map.get(row.getId()));
        }
    }

    @Override
    public void fillCouponBanners(List<CouponAdminDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(CouponAdminDTO::getId).toList();
        Map<Long, Banner> map = mapBySource(TYPE_COUPON, ids);
        for (CouponAdminDTO row : rows) {
            Banner banner = map.get(row.getId());
            if (banner == null) {
                row.setBannerEnabled(false);
                continue;
            }
            row.setBannerEnabled(Integer.valueOf(1).equals(banner.getStatus()));
            row.setBannerImageUrl(banner.getImageUrl());
            row.setBannerTitle(banner.getTitle());
            row.setBannerSubtitle(banner.getSubtitle());
            row.setBannerSort(banner.getSortNo());
            row.setBannerStartTime(formatTime(banner.getStartTime()));
            row.setBannerEndTime(formatTime(banner.getEndTime()));
        }
    }

    @Override
    public void fillSeckillBanners(List<SeckillAdminDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(SeckillAdminDTO::getId).toList();
        Map<Long, Banner> map = mapBySource(TYPE_SECKILL, ids);
        for (SeckillAdminDTO row : rows) {
            Banner banner = map.get(row.getId());
            if (banner == null) {
                row.setBannerEnabled(false);
                continue;
            }
            row.setBannerEnabled(Integer.valueOf(1).equals(banner.getStatus()));
            row.setBannerImageUrl(banner.getImageUrl());
            row.setBannerTitle(banner.getTitle());
            row.setBannerSubtitle(banner.getSubtitle());
            row.setBannerSort(banner.getSortNo());
            row.setBannerStartTime(formatTime(banner.getStartTime()));
            row.setBannerEndTime(formatTime(banner.getEndTime()));
        }
    }

    private void fill(CourseAdminDTO row, Banner banner) {
        if (banner == null) {
            row.setBannerEnabled(false);
            return;
        }
        row.setBannerEnabled(Integer.valueOf(1).equals(banner.getStatus()));
        row.setBannerImageUrl(banner.getImageUrl());
        row.setBannerTitle(banner.getTitle());
        row.setBannerSubtitle(banner.getSubtitle());
        row.setBannerSort(banner.getSortNo());
        row.setBannerStartTime(formatTime(banner.getStartTime()));
        row.setBannerEndTime(formatTime(banner.getEndTime()));
    }

    private Map<Long, Banner> mapBySource(String linkType, List<Long> targetIds) {
        Map<Long, Banner> map = new HashMap<>();
        if (targetIds.isEmpty()) {
            return map;
        }
        for (Banner banner : bannerMapper.selectList(new LambdaQueryWrapper<Banner>()
                .eq(Banner::getLinkType, linkType)
                .in(Banner::getTargetId, targetIds))) {
            map.put(banner.getTargetId(), banner);
        }
        return map;
    }

    /** 支持 yyyy-MM-dd HH:mm 与 yyyy-MM-dd HH:mm:ss；空返回 null */
    private LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "时间格式应为 yyyy-MM-dd HH:mm 或 yyyy-MM-dd HH:mm:ss");
        }
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FMT);
    }
}
