package com.yuedong.course.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 管理端保存教练的入参（新增与修改共用一个 DTO：{@code id} 为空即新增）。
 *
 * <p>★这个类从 {@code fitness-course} 的内部包搬到 api 模块，唯一原因是
 * {@code fitness-admin-server} 要通过 Feign 调 {@code /inner/coach/save}：
 * 契约类型放在服务内部，调用方就只能自己抄一份，抄的那一份从此不会再被编译检查。
 * 和 CourseSaveDTO / StoreSaveDTO 同处一地，正是"接口签名两边同时改，改错编译期就报"的前提。</p>
 *
 * <p>校验注解的作用边界要说清楚：这些 {@code @NotBlank/@Min} 只拦"格式错的请求"，
 * 拦不住业务不合法（比如 storeId 指向一个已关闭的门店、courseIds 里有不存在的课程），
 * 后者必须在 Service 里查库判断。两层都要有，不能只靠注解。</p>
 */
@Data
public class CoachSaveDTO {

    /** 主键；null=新增，非空=修改 */
    private Long id;

    /** 所属门店 */
    @NotNull(message = "请选择所属门店")
    private Long storeId;

    /** 姓名，同店不允许重名（数据库 uk_store_name 兜底） */
    @NotBlank(message = "教练姓名不能为空")
    @Size(max = 20, message = "教练姓名不超过 20 字")
    private String name;

    /** 资质文案 */
    @Size(max = 50, message = "资质文案不超过 50 字")
    private String title;

    /** 简介 */
    @Size(max = 200, message = "简介不超过 200 字")
    private String intro;

    /** 联系电话（仅管理端可见） */
    @Size(max = 20, message = "电话不超过 20 位")
    private String phone;

    /** 照片相对路径；由管理端上传接口返回后回填 */
    @Size(max = 200)
    private String avatarImage;

    /** 无照片时的头像配色下标 0~5 */
    @Size(max = 2, message = "头像配色下标为 0~5")
    private String avatarColor;

    /** 性别：1 男 / 2 女，可不填 */
    @Min(value = 1, message = "性别取 1 男 / 2 女")
    @Max(value = 2, message = "性别取 1 男 / 2 女")
    private Integer gender;

    /** 每日可约起始整点 */
    @NotNull(message = "请设置可约起始时间")
    @Min(value = 0, message = "起始整点为 0~23")
    @Max(value = 23, message = "起始整点为 0~23")
    private Integer workStartHour;

    /** 每日可约结束整点。与起始值的大小关系在 Service 里校验——注解表达不了跨字段规则 */
    @NotNull(message = "请设置可约结束时间")
    @Min(value = 1, message = "结束整点为 1~24")
    @Max(value = 24, message = "结束整点为 1~24")
    private Integer workEndHour;

    /** 可约星期 1~7，前端多选后传列表；为空表示全周可约（在 Service 里给默认值，不让前端兜） */
    private List<Integer> workDays;

    /** 1 可约 / 0 停用 */
    @Min(value = 0, message = "状态取 0 或 1")
    @Max(value = 1, message = "状态取 0 或 1")
    private Integer status;
}
