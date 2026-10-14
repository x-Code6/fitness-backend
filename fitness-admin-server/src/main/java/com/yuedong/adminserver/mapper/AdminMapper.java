package com.yuedong.adminserver.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.adminserver.model.entity.Admin;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 管理员 Mapper。
 *
 * <p>只继承 BaseMapper 就能用 selectById / selectOne / insert / updateById；
 * 这里额外写一条演示账号专用的幂等插入，理由与
 * {@code fitness-user} 的 {@code UserMapper#insertDemoIfAbsent} 相同：
 * BCrypt 散列必须由本服务的 {@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}
 * 现算，不能把散列串写死在 SQL 脚本里。</p>
 */
public interface AdminMapper extends BaseMapper<Admin> {

    /**
     * 写入演示管理员，重复执行不报错（撞主键/uk_username 时不做任何修改）。
     *
     * <p>★用 {@code ON DUPLICATE KEY UPDATE id = id} 而不是 {@code INSERT IGNORE}：
     * IGNORE 会把字段截断、类型错误一起吞掉，出问题时查不到原因。</p>
     *
     * @return 影响行数（新插入=1，已存在=0）
     */
    @Insert("INSERT INTO t_admin (id, username, password, real_name, role, status) "
            + "VALUES (#{id}, #{username}, #{password}, #{realName}, #{role}, 1) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertDemoIfAbsent(@Param("id") Long id,
                           @Param("username") String username,
                           @Param("password") String password,
                           @Param("realName") String realName,
                           @Param("role") String role);
}
