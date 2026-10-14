package com.yuedong.course.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 课程管理端行 DTO：列表展示 + 编辑表单回填。
 *
 * <p>★与 {@link CourseDTO}（给 order 建单用）分开的理由：
 * 两个消费方要的东西不同——管理端要能改的全部字段（含 tags 原始逗号串、上下架状态），
 * 建单只要价格与名称等快照字段。合成一个 DTO 的结果是"哪儿都能用"，
 * 于是谁也不敢改字段，最后变成一个谁也不了解的巨型对象。</p>
 *
 * <p>{@code tags} 在这里<b>保持逗号分隔的原样字符串</b>：管理端表单就是一个文本框，
 * 拆开再拼回去反而容易把用户输入的空格丢掉。C 端才拆成数组（见 {@code CourseVO.tags}）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CourseAdminDTO {

    private Long id;

    private String name;

    /** SALE / CONTENT */
    private String courseKind;

    private String type;

    /** 一节课几小时（1/2/3），管理端下拉 */
    private Integer lessonHours;

    private Integer duration;

    private Integer calories;

    private BigDecimal price;

    /** TIMES / UNLIMITED；CONTENT 为 null */
    private String saleType;

    private Integer timesPerBuy;

    /** 标签，逗号分隔原始串 */
    private String tags;

    private String coverImage;

    private String coverColor;

    private String description;

    private String suitable;

    /** 1 上架 / 0 下架 */
    private Integer status;

    /** 已配置的可售门店数。★列表页用它一眼看出"这门课配了却没上架到任何门店"这种配置事故 */
    private Integer storeCount;

    /**
     * 会教这门课的教练数。
     * <p>★v2 新增：一门课"在卖但没有教练会教"= 买完约不上课，
     * 所以这个 0 必须出现在列表上，而不是等用户投诉才发现。</p>
     */
    private Integer coachCount;

    /** 已配教练 id（编辑表单回填多选框）。★放在分页里省掉"点开表单再查一次"的一次转圈 */
    private List<Long> coachIds;

    /** 这门课在各门店的两层时间窗原始行（表单回填 + "哪家店在卖、什么时候能买"一眼看清） */
    private List<CourseWindowAdminDTO> storeWindows;
}
