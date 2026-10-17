package com.yuedong.course.service;

import com.yuedong.common.model.PageDTO;
import com.yuedong.course.api.dto.CouponAdminDTO;
import com.yuedong.course.api.dto.CouponSaveDTO;
import com.yuedong.course.api.dto.CouponUsableDTO;
import com.yuedong.course.model.vo.CouponVO;
import com.yuedong.course.model.vo.MyCouponVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 优惠券业务接口（v2 五项扩展：类型 / 领取窗口 / 使用窗口 / 限领 N 张 / 可用范围）。
 *
 * <p>★<b>两条领取路径不同</b>（docs 流程 G）：
 * <ul>
 *   <li>SECKILL 券限量，高并发抢，必须走 Redis Lua（判限领 + 扣库存 + 计数三步原子）；</li>
 *   <li>NORMAL 券不限量，领取是低频动作，一句 {@code SELECT COUNT(*)} 判限领后直接 INSERT。
 *       <b>不要用"高级"的方案套在不需要的场景上</b>——给不限量券也写一遍 Lua，
 *       换来的是两套需要各自维护的正确性。</li>
 * </ul>
 */
public interface CouponService {

    /**
     * 领券中心：正在发放的券。
     *
     * @param userId 登录用户（用于"已领几张"与 canReceive）；未登录传 null，
     *               此时 received=0、canReceive 不含个人限领判断
     */
    List<CouponVO> listCenter(Long userId);

    /** 我的券：持券记录 + 模板信息 + 现算的使用截止 */
    List<MyCouponVO> myCoupons(Long userId);

    /**
     * 批量试算：用户手里的券在"这门课 × 这家店 × 这个金额"下能不能用、能抵多少。
     *
     * <p>★判"能不能用"的规则只有 {@link #usableInfo} 一份，本方法<b>只负责循环它</b>。
     * 判断规则有五个来源（金额门槛、券的使用时间窗口、课程白名单、门店白名单、
     * 是否已使用），任何一处复制一份，两处就会随时间漂移，
     * 用户看到的就是"券在列表里是亮的、提交时报不可用"。</p>
     *
     * <p>★只查 {@code status='UNUSED'} 的记录：已经用掉的券没必要出现在选券列表里，
     * 让前端去过滤"不可用"的噪音，等于把数据量当成筛子用。</p>
     *
     * @param amount 该笔订单原价（判门槛）
     * @return 可用的排在前面、按抵扣额从大到小；不可用的附 reason，顺序保持给用户看原因
     */
    List<CouponUsableDTO> usableList(Long userId, Long courseId, Long storeId, BigDecimal amount);

    /** 领券。SECKILL 走 Lua，NORMAL 走 COUNT 判限领 */
    void receive(Long userId, Long couponId);

    /**
     * 内部接口：这张券能不能用在<b>这一笔</b>订单上。
     *
     * @param recordId 持券记录 id
     * @param userId   持券人（★必须校验，否则可以用别人的券）
     * @param amount   该笔订单原价（判门槛）
     * @param courseId 买的哪门课（判 scope=1）。★可为 null，null 时不做课程校验
     * @param storeId  在哪家店买（判 scope=2）。★可为 null，同上
     */
    CouponUsableDTO usableInfo(Long recordId, Long userId, BigDecimal amount,
                               Long courseId, Long storeId);

    /** 内部接口：核销用券（UNUSED → USED，绑定订单号，CAS） */
    boolean use(Long recordId, Long userId, String orderNo);

    /** 内部接口：订单取消/超时后退券（USED → UNUSED）。★已使用的券过期不退 */
    boolean refund(Long recordId);

    /** 管理端：券分页（含 scope 明细，一次 IN 批量取，不逐条查） */
    PageDTO<CouponAdminDTO> adminPage(Integer page, Integer size);

    /** 管理端：创建/编辑券（含两层时间与可用范围；SECKILL 才预热库存） */
    void adminSave(CouponSaveDTO dto);

    /** 管理端：作废券 */
    void adminEnd(Long id);
}
