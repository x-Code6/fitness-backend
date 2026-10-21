package com.yuedong.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuedong.file.model.entity.FileRecord;

/**
 * t_file Mapper：单表增查足够，复杂查询（分页/按分类统计）用 MyBatis-Plus 条件构造器拼。
 *
 * <p>★不写 XML：本表没有联表与自定义 SQL，BaseMapper + LambdaQueryWrapper
 * 能让字段名编译期校验，比手写 SQL 字符串更难写错。</p>
 */
public interface FileRecordMapper extends BaseMapper<FileRecord> {
}
