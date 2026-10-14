package com.yuedong.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.order.model.entity.Rating;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 评价表 Mapper。 */
public interface RatingMapper extends BaseMapper<Rating> {

    /**
     * 软删（只改 status，<b>行保留</b>）。
     *
     * <p>★带 {@code status = 1} 条件：重复点删除第二次影响 0 行，
     * 不会被误当成"又删了一次"而把统计回退两遍。★也必带 user_id：
     * 只按 id 改就成了"猜到评价 id 就能删别人的评价"。</p>
     */
    @Update("UPDATE t_rating SET status = 0 WHERE id = #{ratingId} AND user_id = #{userId} AND status = 1")
    int softDelete(@Param("ratingId") Long ratingId, @Param("userId") Long userId);

    /**
     * 某门课的评价分页取 id 用的排序查询（列表数据用 MyBatis-Plus 条件即可，
     * 这里只留一个"这批订单里哪些已经评过"的批量判断）。
     *
     * <p>★一次 IN 查完，不给"待评价"tab 变成 N 次 EXISTS 子查询的机会。</p>
     */
    @Select("<script>SELECT order_no FROM t_rating WHERE user_id = #{userId} AND status = 1 " +
            "AND order_no IN <foreach collection='orderNos' item='no' open='(' separator=',' close=')'>#{no}</foreach>" +
            "</script>")
    List<String> selectRatedOrderNos(@Param("userId") Long userId,
                                     @Param("orderNos") List<String> orderNos);
}
