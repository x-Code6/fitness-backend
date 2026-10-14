package com.yuedong.course.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 展示型课程评论，对应表 yd_course.t_course_comment。
 *
 * <p>★<b>它和订单评价 {@code t_rating} 是两张表，不共用</b>（D10 vs D11）：
 * 谁能写不同（评论=任何登录用户；评价=必须买过且已支付）、
 * 有没有分值不同（评论无星；评价 1~5 星）、统计口径不同（评论只做列表；评价进门店/教练评分）。
 * <b>长得像不代表是同一个东西</b>——合并的代价是每一处都要先判"这是哪一种"。</p>
 *
 * <p>两级结构靠 {@code rootId} 平铺（一级 root_id=0，回复 root_id=所属一级 id），
 * {@code parentId} 只用来渲染"回复 @某人"那一行，不参与分组。</p>
 */
@Data
@TableName("t_course_comment")
public class CourseComment {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 评论哪门课（同库 → t_course.id） */
    @TableField("course_id")
    private Long courseId;

    /** 所属一级评论 id；<b>自己就是一级时为 0</b> */
    @TableField("root_id")
    private Long rootId;

    @TableField("user_id")
    private Long userId;

    /** 昵称快照（跨库 + 高频读 + 改名要保历史原值） */
    @TableField("user_name")
    private String userName;

    /** 头像配色快照 */
    @TableField("avatar_color")
    private String avatarColor;

    @TableField("content")
    private String content;

    /** 直接回复的评论 id；一级为 null。★只用于渲染，不用于分组 */
    @TableField("parent_id")
    private Long parentId;

    /** 被回复人昵称。<b>服务端从父评论取</b>，绝不接受前端传（前端能传成任何人） */
    @TableField("reply_to_user")
    private String replyToUser;

    /** 1 正常 / 0 已删除（本人可删） */
    @TableField("status")
    private Integer status;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
