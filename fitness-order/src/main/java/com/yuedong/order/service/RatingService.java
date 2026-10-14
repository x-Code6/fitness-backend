package com.yuedong.order.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.order.api.dto.RatingStatsDTO;
import com.yuedong.order.model.dto.RatingSaveDTO;
import com.yuedong.order.model.vo.RatingVO;

import java.util.List;
import java.util.Set;

/**
 * 评价业务接口（D10：按订单评、一单一评、可删不可改）。
 *
 * <p>★这个服务同时是<b>评分统计的唯一写入口</b>：三张汇总表（门店/课程/教练）
 * 只在这里被增减，且与 {@code t_rating} 同一个本地事务。
 * 统计口径一旦有两处写，"明细与统计对不上"就只是时间问题，
 * 而这种 bug 的表现是"用户看了 5 条评价却显示 4.6 分"，最难查也最伤信任。</p>
 */
public interface RatingService {

    /** 提交评价（★一单一评，重复提交由唯一索引拦下） */
    void save(Long userId, RatingSaveDTO dto);

    /** 删除自己的评价（软删，统计同事务回退。删完不能重评——这是"不可改"的实现方式） */
    void delete(Long userId, Long ratingId);

    /** 某门课的评价分页 */
    PageDTO<RatingVO> courseRatings(Long userId, Long courseId, Integer page, Integer size);

    /** 我发过的评价 */
    List<RatingVO> mine(Long userId);

    /** 这批订单里哪些已经评过（订单列表"待评价"tab 与详情页 rated 字段用，一次查完） */
    Set<String> ratedOrderNos(Long userId, List<String> orderNos);

    /**
     * 评分统计（内部接口给 course 服务）。
     *
     * @param type STORE / COURSE / COACH
     * @param ids  目标 id
     */
    List<RatingStatsDTO> stats(String type, List<Long> ids);
}
