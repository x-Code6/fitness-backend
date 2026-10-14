package com.yuedong.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.user.model.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 用户表 Mapper。
 *
 * <p>继承 BaseMapper&lt;User&gt; 后，MyBatis-Plus 在启动时为接口生成动态代理，
 * 直接拥有 selectById / selectOne / insert / updateById / selectBatchIds 等
 * 单表 CRUD 能力，无需写 XML。复杂 SQL（多表 join、统计）再写 XML 或注解 SQL。</p>
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 写入演示账号，主键 id 显式指定，重复执行不报错（幂等）。
     *
     * <p>为什么要自己写这条 SQL 而不用 MP 的 {@code insert(User)}：种子数据要求演示账号的
     * id 固定是 10001/10002（{@code yd_order} 里的历史订单/凭证/预约/评价都引用了这两个 id），
     * 而 MP 在 {@code IdType.AUTO} 下会不会把非空的 id 一起写进 INSERT，取决于版本对自增主键的处理，
     * 这种"必须精确"的场景就直接把列名写清楚，不靠推断。</p>
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id = id} 是 MySQL 语法：撞 uk_phone 或主键时做一个
     * 无变化的更新，等于什么都不做。这样服务每次重启都跑一遍初始化器也不会报错，
     * 也不会把用户改过的昵称密码覆盖掉。
     * ★注意不要用 {@code INSERT IGNORE}——它连字段截断、类型错误一起吞掉，出问题时查不到。</p>
     *
     * @return 影响行数（新插入=1，已存在=0）
     */
    @Insert("INSERT INTO t_user (id, phone, password, nickname, avatar_color, status) "
            + "VALUES (#{id}, #{phone}, #{password}, #{nickname}, #{avatarColor}, 1) "
            + "ON DUPLICATE KEY UPDATE id = id")
    int insertDemoIfAbsent(@Param("id") Long id,
                           @Param("phone") String phone,
                           @Param("password") String password,
                           @Param("nickname") String nickname,
                           @Param("avatarColor") String avatarColor);
}
