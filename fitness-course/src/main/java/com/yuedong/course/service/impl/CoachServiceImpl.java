package com.yuedong.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.course.mapper.CoachCourseMapper;
import com.yuedong.course.mapper.CoachMapper;
import com.yuedong.course.mapper.CourseMapper;
import com.yuedong.course.model.dto.CoachSaveDTO;
import com.yuedong.course.model.entity.Coach;
import com.yuedong.course.model.entity.CoachCourse;
import com.yuedong.course.model.vo.CoachVO;
import com.yuedong.course.service.CoachService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 教练业务实现。
 *
 * <p>本类只做两件事：教练身份的增改查 + 会教范围维护。
 * 时段占用不在这里（那是 order 服务的 {@code t_coach_busy}），
 * 所以本类<b>不需要任何并发防护</b>——配课是低频管理动作，
 * 唯一约束交给数据库索引即可（并发防御三档里的"不防"档）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoachServiceImpl implements CoachService {

    private static final String DEFAULT_WORK_DAYS = "1,2,3,4,5,6,7";
    private static final String DEFAULT_AVATAR_COLOR = "0";

    private final CoachMapper coachMapper;
    private final CoachCourseMapper coachCourseMapper;
    private final CourseMapper courseMapper;

    @Override
    public List<CoachVO> listByCourseAndStore(Long courseId, Long storeId) {
        // 一条 JOIN 查完：t_coach × t_coach_course（同库可 JOIN，见 CoachMapper 的注释）
        // 绝不允许"先查关联表拿 id 列表，再循环 selectById"——那是典型的 N+1
        List<Coach> coaches = coachMapper.selectByCourseAndStore(courseId, storeId);

        List<CoachVO> result = new ArrayList<>();
        for (Coach coach : coaches) {
            // 逐个转 VO。这里用 for 循环而不是 stream，是因为字段有 8 个，
            // builder 链套在 stream 里可读性明显变差（第 12 条：可读性优先）
            CoachVO vo = CoachVO.builder()
                    .coachId(coach.getId())
                    .name(coach.getName())
                    .title(coach.getTitle())
                    .intro(coach.getIntro())
                    .avatarImage(coach.getAvatarImage())
                    .avatarColor(coach.getAvatarColor())
                    .workStartHour(coach.getWorkStartHour())
                    .workEndHour(coach.getWorkEndHour())
                    // freeTodayCount / nextFreeAt 暂不填：占用数据在 yd_order，
                    // 等预约域接口接上后由那边批量查出再填。留 null，前端按"暂无时段数据"渲染
                    .build();
            result.add(vo);
        }
        return result;
    }

    /**
     * 内部接口用：返回实体，因为 order 服务要 {@code workDays} 这个计算列
     * （C 端 VO 里没有它，理由见接口注释）。
     */
    @Override
    public List<Coach> listBookable(Long courseId, Long storeId) {
        // 一条 JOIN 查完，只含 status=1：过滤条件写在 SQL 里而不是内存里筛，
        // 这样"可约教练"的口径在课程服务内只有一份定义
        return coachMapper.selectByCourseAndStore(courseId, storeId);
    }

    @Override
    public Long save(CoachSaveDTO dto) {
        // ===== 第 1 步：跨字段校验。注解表达不了"结束必须晚于起始"这种关系，只能写在代码里 =====
        if (dto.getWorkEndHour() <= dto.getWorkStartHour()) {
            throw new BusinessException(ErrorCode.COACH_WORK_TIME_INVALID,
                    "可约结束时间必须晚于起始时间");
        }

        // ===== 第 2 步：把入参拼成实体。可空字段在这里给默认值，不留给前端兜 =====
        Coach coach = new Coach();
        coach.setId(dto.getId());
        coach.setStoreId(dto.getStoreId());
        coach.setName(dto.getName().trim());
        coach.setTitle(dto.getTitle());
        coach.setIntro(dto.getIntro());
        coach.setPhone(dto.getPhone());
        coach.setAvatarImage(dto.getAvatarImage());
        coach.setAvatarColor(blankToDefault(dto.getAvatarColor(), DEFAULT_AVATAR_COLOR));
        coach.setGender(dto.getGender());
        coach.setWorkStartHour(dto.getWorkStartHour());
        coach.setWorkEndHour(dto.getWorkEndHour());
        // 星期列表转成库里的逗号串；空列表按"全周可约"处理
        coach.setWorkDays((dto.getWorkDays() == null || dto.getWorkDays().isEmpty())
                ? DEFAULT_WORK_DAYS
                : dto.getWorkDays().stream().map(String::valueOf).collect(Collectors.joining(",")));
        coach.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        coach.setUpdatedAt(LocalDateTime.now());

        if (coach.getId() == null) {
            // ===== 第 3 步a：新增。重名靠唯一索引 uk_store_name 兜底，不先查再插 =====
            // "先 selectCount 判断再 insert"在并发下两个请求可能同时判过，最终防线是索引
            coach.setCreatedAt(LocalDateTime.now());
            try {
                coachMapper.insert(coach);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.COACH_NAME_DUPLICATE);
            }
            log.info("新增教练: id={}, storeId={}, name={}", coach.getId(), coach.getStoreId(), coach.getName());
            return coach.getId();
        }

        // ===== 第 3 步b：修改。updateById 只写非 null 字段，不会把没提交的列清掉 =====
        coach.setCreatedAt(null);   // 创建时间不允许被这次更新覆盖
        int rows = coachMapper.updateById(coach);
        if (rows == 0) {
            // 影响 0 行说明这个 id 不存在（或已被停用后删除），不能当成"保存成功"返回
            throw new BusinessException(ErrorCode.COACH_NOT_FOUND);
        }
        log.info("修改教练: id={}, name={}", coach.getId(), coach.getName());
        return coach.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignCourses(Long coachId, List<Long> courseIds) {
        // ===== 第 1 步：教练必须存在。查一次，命中主键 =====
        Coach coach = coachMapper.selectById(coachId);
        if (coach == null) {
            throw new BusinessException(ErrorCode.COACH_NOT_FOUND);
        }

        // ===== 第 2 步：整体替换语义——先删干净，再按新列表插 =====
        // 不做差异计算（哪些要删哪些要加）：配课量小（一个教练几门课），
        // 全删重插的代价可以忽略，而差异逻辑复杂且容易漏，收益不匹配
        coachCourseMapper.deleteByCoachId(coachId);

        if (courseIds == null || courseIds.isEmpty()) {
            log.info("清空教练配课: coachId={}", coachId);
            return;
        }

        for (Long courseId : courseIds) {
            // ===== 第 3 步：逐条判课程是否存在 =====
            // 课程 id 来自管理端下拉，正常情况一定存在；这里判一下是边界防御——
            // 管理员在页面上停留期间课程可能被人删了，那不该静默插出一条孤儿关联
            if (courseMapper.countById(courseId) == 0) {
                throw new BusinessException(ErrorCode.COURSE_NOT_FOUND, "课程不存在：" + courseId);
            }
            CoachCourse relation = new CoachCourse();
            relation.setCoachId(coachId);
            relation.setCourseId(courseId);
            relation.setCreatedAt(LocalDateTime.now());
            try {
                coachCourseMapper.insert(relation);
            } catch (DuplicateKeyException e) {
                // 前端传了重复的课程 id。上面已经先全删了，理论上不该撞；
                // 撞了就说明同一请求里 id 重复，跳过即可，不必让整个操作失败
                log.warn("配课重复，已忽略: coachId={}, courseId={}", coachId, courseId);
            }
        }
        log.info("教练配课已更新: coachId={}, 课程数={}", coachId, courseIds.size());
    }

    @Override
    public List<Long> courseIdsOf(Long coachId) {
        // 查关联表：WHERE coach_id=? —— 走 uk_coach_course 的最左前缀
        List<CoachCourse> relations = coachCourseMapper.selectList(
                new LambdaQueryWrapper<CoachCourse>().eq(CoachCourse::getCoachId, coachId));

        List<Long> courseIds = new ArrayList<>();
        for (CoachCourse relation : relations) {
            courseIds.add(relation.getCourseId());
        }
        return courseIds;
    }

    @Override
    public List<Coach> listByStore(Long storeId) {
        // 查 t_coach：WHERE store_id=? ORDER BY id。命中 uk_store_name 的最左前缀(store_id)，
        // 顺序稳定，管理端列表刷新不会出现跳动
        return coachMapper.selectList(new LambdaQueryWrapper<Coach>()
                .eq(Coach::getStoreId, storeId)
                .orderByAsc(Coach::getId));
    }

    /** 空字符串与 null 一律回退到默认值（头像配色这类"没填就要给个默认"的字段） */
    private String blankToDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
