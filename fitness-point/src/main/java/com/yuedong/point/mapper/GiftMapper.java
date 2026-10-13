package com.yuedong.point.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.point.model.entity.Gift;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 积分商城商品 Mapper：库存原子扣减。 */
public interface GiftMapper extends BaseMapper<Gift> {

    @Update("UPDATE t_gift SET stock = stock - 1 WHERE id = #{giftId} AND stock > 0")
    int deductStock(@Param("giftId") Long giftId);
}
