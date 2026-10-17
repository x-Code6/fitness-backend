package com.yuedong.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.user.model.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 健身档案表 Mapper。
 * <p>只有主键读写，不需要自定义 SQL：{@code selectById(userId)} 就能取到一个人的档案，
 * 保存走 insertOrUpdate（见 UserProfileServiceImpl）。★不要为了"看起来完整"加一堆没人用的 XML。</p>
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {
}
