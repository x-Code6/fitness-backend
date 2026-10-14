package com.yuedong.course.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 管理端教练分页行（教练表 + 门店名 + 配课情况三处聚合）。
 *
 * <p>★为什么这里给的是 DTO 而不是直接返回 {@code Coach} 实体：
 * 管理页要显示"在哪家店""会教哪几门课"，这两样都是<b>关联表的名字</b>，
 * 实体里只有 id。把聚合结果做成一个显式的 DTO，比在实体上加一堆
 * {@code @TableField(exist = false)} 干净——后者会让"这个字段来自哪张表"重新变得看不出来。</p>
 *
 * <p>★{@code workDays} 出 {@code List<Integer>} 而不是库里的 "1,2,3" 字符串：
 * 管理端表单是多选框，两边都用列表，转换只在 course 服务这一次做完。</p>
 */
@Data
@Builder
public class CoachAdminDTO {

    private Long id;

    private Long storeId;

    /** 门店名（同库 JOIN t_store 得到） */
    private String storeName;

    private String name;

    private String title;

    private String intro;

    /** ★只有管理端接口带这个字段，C 端 CoachVO 里没有教练电话 */
    private String phone;

    private String avatarImage;

    private String avatarColor;

    /** 1 男 / 2 女 / null 未填 */
    private Integer gender;

    private Integer workStartHour;

    private Integer workEndHour;

    /** 可约星期（1=周一） */
    private List<Integer> workDays;

    /** 1 可约 / 0 停用 */
    private Integer status;

    /** 会教几门课（列表上"下架前先看这位还挂着多少课"） */
    private Integer courseCount;

    /** 课程名顿号串，表格里一列显示完 */
    private String courseNames;

    /** 已配课程 id，编辑表单回填多选框用 */
    private List<Long> courseIds;
}
