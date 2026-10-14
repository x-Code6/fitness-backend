package com.yuedong.order.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交评价的入参（对应前端 {@code POST /rating/save}）。
 *
 * <p>★<b>没有 ratingId、也没有"修改"接口</b>：一单一评、可删不可改（D10）。
 * 想改只能删了之后重评？也不行——软删保留行，唯一键继续生效，删完就再也没有第二次。
 * <b>"只能评一次"这件事必须由数据层保证，靠接口不暴露 update 是防不住的。</b></p>
 */
@Data
public class RatingSaveDTO {

    /** 评的是哪张订单（★是订单号不是凭证/预约号：评价的是"这次购买+上课"的整体体验） */
    @NotBlank(message = "缺少订单号")
    private String orderNo;

    /** 1~5 星。★范围用注解硬拦，不给"打个 0 分把店踩死"或"打 7 分"留口子 */
    @NotNull
    @Min(value = 1, message = "评分最低 1 星")
    @Max(value = 5, message = "评分最高 5 星")
    private Integer score;

    /** 评价内容。★故意不要求必填：只给星级不给话是最常见的评价行为，强制写会压低评价率 */
    @Size(max = 300, message = "评价内容最多 300 字")
    private String comment;

    /**
     * 顺手选的那位教练，可空。
     * <p>★服务层要校验"这位教练确实出现在该单的已完成预约里"，
     * 否则用户可以给一个陌生教练刷好评。</p>
     */
    private Long coachId;
}
