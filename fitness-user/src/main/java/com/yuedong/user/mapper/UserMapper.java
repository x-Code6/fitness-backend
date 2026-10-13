package com.yuedong.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.user.model.entity.User;

/**
 * 用户表 Mapper。
 *
 * <p>继承 BaseMapper&lt;User&gt; 后，MyBatis-Plus 在启动时为接口生成动态代理，
 * 直接拥有 selectById / selectOne / insert / updateById / selectBatchIds 等
 * 单表 CRUD 能力，无需写 XML。复杂 SQL（多表 join、统计）再写 XML 或注解 SQL。</p>
 */
public interface UserMapper extends BaseMapper<User> {
}
