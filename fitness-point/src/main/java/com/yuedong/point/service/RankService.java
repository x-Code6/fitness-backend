package com.yuedong.point.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.point.model.vo.RankItemVO;

import java.util.List;

/**
 * 排行榜业务接口。
 *
 * <p>★两条读口对应两个数据源，这条分工是 D17 的全部意义：
 * <b>本月读 Redis ZSet（实时、要排序要增量更新），历史月读 {@code t_rank_archive}
 * （已结算，不再变，所以不需要 Redis 那份热数据）</b>。
 * 旧实现只有实时榜，Redis 一清就"这个月的榜单没了"，而上个月的榜更是从来没存过。</p>
 */
public interface RankService {

    /**
     * 月度挑战赛积分榜（ZSet 实时榜）。
     * 返回 Top50；若当前用户在 50 名以外，会追加到列表末尾（带真实名次）。
     */
    List<RankItemVO> monthly(Long userId);

    /**
     * 历史月榜单（归档表，分页）。
     *
     * @param season 月份 yyyyMM，如 202609
     * @param userId 当前登录用户，用来标"这一行是不是我"（历史榜也要一眼看到自己排第几）
     */
    PageDTO<RankItemVO> history(String season, Integer page, Integer size, Long userId);

    /**
     * 归档上一个自然月的榜单（定时任务调用）。
     *
     * @return 归档的用户数；0 表示那个月没有榜数据（新月份或 Redis 已被清）
     */
    int archiveLastMonth();
}
