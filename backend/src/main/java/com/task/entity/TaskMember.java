package com.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_member")
public class TaskMember {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Long userId;
    private Integer weight;
    private Integer progress;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
