package com.yuedong.point.service;

import com.yuedong.point.model.vo.RankItemVO;

import java.util.List;

/**
 * 排行榜业务接口。
 */
public interface RankService {

    /**
     * 月度挑战赛积分榜（ZSet 实时榜）。
     * 返回 Top50；若当前用户在 50 名以外，会追加到列表末尾（带真实名次）。
     */
    List<RankItemVO> monthly(Long userId);
}
