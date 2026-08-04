package com.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.task.entity.Report;
import org.apache.ibatis.annotations.Select;

public interface ReportMapper extends BaseMapper<Report> {

    /** 锁汇报行：撤回/编辑/重提/审核并发时保证只有一个状态转换成功 */
    @Select("SELECT * FROM report WHERE id = #{id} FOR UPDATE")
    Report selectByIdForUpdate(Long id);
}
