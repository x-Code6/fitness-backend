package com.yuedong.behavior.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.behavior.model.entity.BehaviorLog;
import org.apache.ibatis.annotations.Insert;

/** 行为日志 Mapper。 */
public interface BehaviorLogMapper extends BaseMapper<BehaviorLog> {

    /**
     * 幂等插入：{@code uk_event(event_id)} 撞键时不抛异常，直接按重复处理。
     *
     * <p>★不用 {@code INSERT IGNORE}：它会把字段超长、JSON 非法等错误也一起吞掉，
     * 脏数据会静默消失。这里只忽略"主键/唯一键冲突"这一种情况。</p>
     *
     * <p>★返回值的 0/1 依赖 MySQL 默认 {@code useAffectedRows=false}（本项目 JDBC URL 未改）；
     * 若以后开启它，重复行可能被算成 1，需要改用别的判断方式。</p>
     *
     * @return 1=新插入；0=event_id 已存在（重复事件）
     */
    @Insert("INSERT INTO t_behavior_log " +
            "(event_id, event_type, target_id, device_id, user_id, client_ts, props, created_at) " +
            "VALUES (#{eventId}, #{eventType}, #{targetId,jdbcType=BIGINT}, #{deviceId}, " +
            "#{userId,jdbcType=BIGINT}, #{clientTs,jdbcType=BIGINT}, #{props,jdbcType=VARCHAR}, #{createdAt}) " +
            "ON DUPLICATE KEY UPDATE event_id = event_id")
    int insertIgnoreDuplicate(BehaviorLog logRow);
}
