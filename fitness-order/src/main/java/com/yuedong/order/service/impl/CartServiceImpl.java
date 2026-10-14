package com.yuedong.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuedong.common.exception.BusinessException;
import com.yuedong.common.result.ErrorCode;
import com.yuedong.common.result.Result;
import com.yuedong.course.api.client.CourseClient;
import com.yuedong.course.api.dto.CourseDTO;
import com.yuedong.course.api.dto.CourseStoreKeyDTO;
import com.yuedong.order.constant.OrderConstants;
import com.yuedong.order.mapper.CartItemMapper;
import com.yuedong.order.model.dto.BookingCreateDTO;
import com.yuedong.order.model.dto.CartAddDTO;
import com.yuedong.order.model.dto.CartItemUpdateDTO;
import com.yuedong.order.model.dto.CheckoutDTO;
import com.yuedong.order.model.entity.CartItem;
import com.yuedong.order.model.vo.BookingVO;
import com.yuedong.order.model.vo.CartItemVO;
import com.yuedong.order.model.vo.CartListVO;
import com.yuedong.order.model.vo.CheckoutResultVO;
import com.yuedong.order.service.BookingService;
import com.yuedong.order.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 购物车实现（D12）。
 *
 * <p>★<b>结算拆成 N 张订单 + 一个 parentNo</b>，不建父订单表：
 * 父表会立刻带来一条新的状态机（"父单已取消而子单还在待支付"谁来管？），
 * 既然答案是"没人管、只能容忍不一致"，那就一开始不要它。
 * 支付与取消都用一条 {@code UPDATE ... WHERE parent_no=?} 整组流转，
 * 效果一样，状态却少一个。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CartItemMapper cartItemMapper;
    private final CourseClient courseClient;
    private final BookingService bookingService;

    // =====================================================================
    // 加购 / 改 / 删
    // =====================================================================
    @Override
    public CartItemVO add(Long userId, CartAddDTO dto) {
        int quantity = dto.getQuantity() == null || dto.getQuantity() < 1
                ? 1 : Math.min(dto.getQuantity(), OrderConstants.MAX_QUANTITY_PER_ITEM);

        // ===== 第 1 步：可售校验（★加购就要拦，不要留到结算页才报错）=====
        // 用户在购物车里攒了一堆、点结算才告诉他"这门课已停售"，是最差的一串交互
        CourseDTO course = fetchSnapshot(dto.getCourseId(), dto.getStoreId());
        if (course == null) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        if (!"SALE".equals(course.getCourseKind())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "展示型课程无需购买");
        }
        if (!Boolean.TRUE.equals(course.getOnSale())) {
            throw new BusinessException(ErrorCode.COURSE_NOT_ON_SALE);
        }

        // ===== 第 2 步：同课同店已有条目就累加份数，否则插入新行 =====
        CartItem existing = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .eq(CartItem::getCourseId, dto.getCourseId())
                .eq(CartItem::getStoreId, dto.getStoreId()));
        if (existing != null) {
            // 累加后再封顶：连点五次"+"不应该变成 50 份
            existing.setQuantity(Math.min(
                    (existing.getQuantity() == null ? 0 : existing.getQuantity()) + quantity,
                    OrderConstants.MAX_QUANTITY_PER_ITEM));
            existing.setSelected(1);
            // 改：更新份数与勾选状态，命中 uk_user_course_store 定位这一条
            cartItemMapper.updateById(existing);
            return toVO(existing, course);
        }

        // ===== 第 3 步：条目数上限 =====
        Long count = cartItemMapper.selectCount(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId));
        if (count != null && count >= OrderConstants.MAX_CART_ITEMS) {
            throw new BusinessException(ErrorCode.CART_OVER_LIMIT,
                    "购物车最多放 " + OrderConstants.MAX_CART_ITEMS + " 条，先清一清");
        }
        CartItem item = new CartItem();
        item.setUserId(userId);
        item.setCourseId(dto.getCourseId());
        item.setStoreId(dto.getStoreId());
        item.setQuantity(quantity);
        item.setSelected(1);
        item.setCreatedAt(LocalDateTime.now());
        // 增：写购物车。★并发下同一用户同时加同一课同店可能撞唯一键，
        // 撞了就是"已经加过了"，让用户刷新页面即可，不做静默重试
        cartItemMapper.insert(item);
        return toVO(item, course);
    }

    @Override
    public void update(Long userId, CartItemUpdateDTO dto) {
        CartItem item = loadOwned(userId, dto.getItemId());

        // ★"传了才改"：前端加减号只发 quantity、勾选框只发 selected，
        // 不传的字段必须保持原值，否则一次勾选会把份数重置成 1
        if (dto.getQuantity() != null) {
            item.setQuantity(Math.min(Math.max(dto.getQuantity(), 1), OrderConstants.MAX_QUANTITY_PER_ITEM));
        }
        if (dto.getSelected() != null) {
            item.setSelected(dto.getSelected() == 0 ? 0 : 1);
        }
        // 改：更新条目自身，命中主键
        cartItemMapper.updateById(item);
    }

    @Override
    public void remove(Long userId, Long itemId) {
        // 先按 id + user_id 确认归属再删。★直接 deleteById 就成了"传谁的 id 删谁的条目"
        CartItem item = loadOwned(userId, itemId);
        cartItemMapper.deleteById(item.getId());
        log.info("购物车条目已删除: itemId={}, userId={}", itemId, userId);
    }

    // =====================================================================
    // 列表
    // =====================================================================
    @Override
    public CartListVO list(Long userId) {
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .orderByDesc(CartItem::getId));
        if (items.isEmpty()) {
            return CartListVO.builder()
                    .items(new ArrayList<>())
                    .totalAmount(BigDecimal.ZERO)
                    .totalAmountText(formatMoney(BigDecimal.ZERO))
                    .selectedCount(0)
                    .totalQuantity(0)
                    .emptyText("购物车还是空的，去课程页挑一节")
                    .build();
        }

        // ★一次批量 RPC 拿全部 (课,店) 快照。逐条调 detail 会把 20 条变成 20 次网络往返，
        // 那和循环查库是同一个毛病，而且更贵
        Map<String, CourseDTO> snapshots = loadSnapshots(items);

        List<CartItemVO> rows = new ArrayList<>(items.size());
        BigDecimal totalAmount = BigDecimal.ZERO;
        int selectedCount = 0;
        int totalQuantity = 0;
        for (CartItem item : items) {
            CourseDTO course = snapshots.get(key(item.getCourseId(), item.getStoreId()));
            CartItemVO vo = toVO(item, course);
            rows.add(vo);
            // 合计只算"勾选且在售"的：停售条目显示金额但不进合计，
            // 否则用户按结算按钮时会发现要付的比看到的多
            if (Boolean.TRUE.equals(vo.getSelected()) && Boolean.TRUE.equals(vo.getOnSale())) {
                totalAmount = totalAmount.add(vo.getAmount());
                selectedCount++;
                totalQuantity += item.getQuantity();
            } else if (Boolean.TRUE.equals(vo.getSelected())) {
                totalQuantity += item.getQuantity();
            }
        }

        return CartListVO.builder()
                .items(rows)
                .totalAmount(totalAmount)
                .totalAmountText(formatMoney(totalAmount))
                .selectedCount(selectedCount)
                .totalQuantity(totalQuantity)
                .emptyText("")
                .build();
    }

    // =====================================================================
    // 结算
    // =====================================================================
    @Override
    public CheckoutResultVO checkout(Long userId, CheckoutDTO dto) {
        List<CheckoutDTO.Line> lines = dto.getItems();
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.CART_EMPTY);
        }

        // ===== 第 1 步：按 id 批量取条目，★user_id 必带（防越权结算别人的车）=====
        List<Long> itemIds = new ArrayList<>(lines.size());
        for (CheckoutDTO.Line line : lines) {
            itemIds.add(line.getCartItemId());
        }
        List<CartItem> items = cartItemMapper.selectList(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getUserId, userId)
                .in(CartItem::getId, itemIds));
        if (items.size() != itemIds.size()) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        Map<Long, CartItem> itemMap = new HashMap<>();
        for (CartItem item : items) {
            itemMap.put(item.getId(), item);
        }

        // ===== 第 2 步：批量取快照做前置校验，让用户一次看到所有问题，而不是一单一单地报错 =====
        Map<String, CourseDTO> snapshots = loadSnapshots(items);
        for (CheckoutDTO.Line line : lines) {
            CartItem item = itemMap.get(line.getCartItemId());
            CourseDTO course = snapshots.get(key(item.getCourseId(), item.getStoreId()));
            if (course == null || !Boolean.TRUE.equals(course.getOnSale())) {
                throw new BusinessException(ErrorCode.COURSE_NOT_ON_SALE,
                        "「" + (course == null ? item.getCourseId() : course.getName())
                                + "」已停售，请先从购物车移除");
            }
        }

        // ===== 第 3 步：逐条建单，同一 parentNo =====
        // ★为什么不套一个大事务：create() 内部有一次跨服务写（核销券）。
        // 把 N 次建单包进一个事务只会让行锁与远程调用一起被拖长，
        // 而且仍然修不掉"券已在远端核销但本地回滚"这一处。
        // 所以这里选择<b>快速失败</b>：中途失败时前面已建的是待支付单，
        // 15 分钟后由延迟消息自动取消——系统会自愈，不需要用户理解发生了什么。
        String parentNo = genParentNo();
        List<BookingVO> orders = new ArrayList<>(lines.size());
        BigDecimal payAmount = BigDecimal.ZERO;
        int entitlementTotal = 0;
        for (CheckoutDTO.Line line : lines) {
            CartItem item = itemMap.get(line.getCartItemId());
            BookingCreateDTO createDto = new BookingCreateDTO();
            createDto.setCourseId(item.getCourseId());
            createDto.setStoreId(item.getStoreId());
            createDto.setQuantity(item.getQuantity());
            // ★券按子单各一张：门槛与范围都是"这张券 × 这笔订单"的关系
            createDto.setCouponRecordId(line.getCouponRecordId());

            BookingVO order = bookingService.create(userId, createDto, parentNo);
            orders.add(order);
            payAmount = payAmount.add(order.getPayAmount());
            entitlementTotal += item.getQuantity() == null ? 1 : item.getQuantity();
        }

        // ===== 第 4 步：单都建好了才清购物车（顺序反了会出现"车空了但没有单"）=====
        for (Long itemId : itemIds) {
            // 删：按主键删条目（归属在第 1 步已经整体校验过）
            cartItemMapper.deleteById(itemId);
        }

        log.info("购物车结算完成: parentNo={}, userId={}, 单数={}, 合计={}",
                parentNo, userId, orders.size(), payAmount);
        return CheckoutResultVO.builder()
                .parentNo(parentNo)
                .orders(orders)
                .payAmount(payAmount)
                .payAmountText(formatMoney(payAmount))
                .entitlementTotal(entitlementTotal)
                .build();
    }

    // =====================================================================
    // 私有工具
    // =====================================================================

    /** 一条 (课,店) 快照，取不到返回 null（由调用方按"已下架"处理） */
    private CourseDTO fetchSnapshot(Long courseId, Long storeId) {
        Result<CourseDTO> result = courseClient.detail(courseId, storeId);
        return result.getCode() == 0 ? result.getData() : null;
    }

    /** 批量快照：把条目集合变成 (课,店) 对，一次 RPC 取回，再按组合键建索引 */
    private Map<String, CourseDTO> loadSnapshots(List<CartItem> items) {
        List<CourseStoreKeyDTO> keys = new ArrayList<>(items.size());
        for (CartItem item : items) {
            keys.add(CourseStoreKeyDTO.builder()
                    .courseId(item.getCourseId())
                    .storeId(item.getStoreId())
                    .build());
        }
        Result<List<CourseDTO>> result = courseClient.batchDetail(keys);
        Map<String, CourseDTO> map = new HashMap<>();
        if (result.getCode() != 0 || result.getData() == null) {
            return map;
        }
        for (CourseDTO course : result.getData()) {
            map.put(key(course.getId(), course.getStoreId()), course);
        }
        return map;
    }

    private String key(Long courseId, Long storeId) {
        return courseId + "_" + storeId;
    }

    /** 按 id + 用户取条目。★user_id 不能省，否则就是"猜到 id 就能改别人的车" */
    private CartItem loadOwned(Long userId, Long itemId) {
        CartItem item = cartItemMapper.selectOne(new LambdaQueryWrapper<CartItem>()
                .eq(CartItem::getId, itemId)
                .eq(CartItem::getUserId, userId));
        if (item == null) {
            throw new BusinessException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
        return item;
    }

    /** 实体 + 课程快照 → VO。course 为 null 时按"已下架"给一份能渲染的 VO（★不能让整页报错） */
    private CartItemVO toVO(CartItem item, CourseDTO course) {
        boolean onSale = course != null && Boolean.TRUE.equals(course.getOnSale());
        int quantity = item.getQuantity() == null ? 1 : item.getQuantity();
        BigDecimal unitPrice = course == null || course.getPrice() == null
                ? BigDecimal.ZERO : course.getPrice();
        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(quantity));

        return CartItemVO.builder()
                .itemId(item.getId())
                .courseId(item.getCourseId())
                .courseName(course == null ? "课程已下架" : course.getName())
                .courseType(course == null ? "" : course.getType())
                .coverColor(course == null ? "0" : course.getCoverColor())
                .lessonHours(course == null ? null : course.getLessonHours())
                .saleType(course == null ? null : course.getSaleType())
                .unitPrice(unitPrice)
                .unitPriceText(formatMoney(unitPrice))
                .storeId(item.getStoreId())
                .storeName(course == null ? "" : course.getStoreName())
                .quantity(quantity)
                .selected(item.getSelected() != null && item.getSelected() == 1)
                .amount(amount)
                .amountText(formatMoney(amount))
                .onSale(onSale)
                .offSaleText(onSale ? "" : "已停售")
                .validText(course == null ? "" : validText(course))
                .build();
    }

    /** 有效期说明。★三种 valid_type 读三组列，和凭证那边同一套语义，不能自己发挥 */
    private String validText(CourseDTO course) {
        if (course.getValidType() == null) {
            return "";
        }
        switch (course.getValidType()) {
            case "FOREVER":
                return "长期有效";
            case "DAYS":
                return course.getValidDays() == null ? "有效期以门店告知为准"
                        : "支付后 " + course.getValidDays() + " 天内可用";
            case "RANGE":
                return course.getUseEndTime() == null ? "有效期以门店告知为准"
                        : "可用至 " + course.getUseEndTime().format(DAY_FMT);
            default:
                return "";
        }
    }

    private String formatMoney(BigDecimal amount) {
        return amount == null ? "¥0.00" : String.format("¥%.2f", amount);
    }

    /** 组号：P + 时间戳 + 3 位随机。★不是订单号，只用于整组流转，所以不必保证全局唯一到业务级别 */
    private String genParentNo() {
        return "P" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(100, 1000);
    }
}
