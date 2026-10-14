package com.yuedong.point.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.point.model.entity.CheckinRecord;

/**
 * 打卡记录 Mapper。
 *
 * <p>只用通用能力就够：判重靠 {@code insert} 撞唯一索引，日历靠
 * {@code WHERE user_id=? AND check_date BETWEEN 月初 AND 月末} 的范围查询
 * （走 {@code uk_user_date}，等值在前、范围在后）。
 * ★<b>没有</b> "先查今天有没有、再决定插不插" 的写法——查过的结果在并发下不作数。</p>
 */
public interface CheckinRecordMapper extends BaseMapper<CheckinRecord> {
}
