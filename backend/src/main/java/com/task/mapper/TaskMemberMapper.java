package com.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.task.entity.TaskMember;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TaskMemberMapper extends BaseMapper<TaskMember> {

    @Select("SELECT * FROM task_member WHERE id = #{id} FOR UPDATE")
    TaskMember selectByIdForUpdate(Long id);

    /** 锁某任务的全部成员行（删除任务时与提交汇报共用 task→member 锁顺序） */
    @Select("SELECT * FROM task_member WHERE task_id = #{taskId} FOR UPDATE")
    List<TaskMember> selectByTaskIdForUpdate(Long taskId);

    /** 提交汇报时锁成员行，与删除任务的锁顺序一致（task → member） */
    @Select("SELECT * FROM task_member WHERE task_id = #{taskId} AND user_id = #{userId} FOR UPDATE")
    TaskMember selectByTaskAndUserForUpdate(@Param("taskId") Long taskId, @Param("userId") Long userId);
}
