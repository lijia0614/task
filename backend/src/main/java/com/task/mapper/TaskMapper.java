package com.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.task.entity.Task;
import org.apache.ibatis.annotations.Select;

public interface TaskMapper extends BaseMapper<Task> {

    /** 锁行读取（原生 SQL 不过滤逻辑删除，deleted 由调用方校验） */
    @Select("SELECT * FROM task WHERE id = #{id} FOR UPDATE")
    Task selectByIdForUpdate(Long id);
}
