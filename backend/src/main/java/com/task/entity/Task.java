package com.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task")
public class Task {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private Long creatorId;
    private String assignType;   // INDIVIDUAL / GROUP
    private Long assigneeId;
    private String status;       // DOING / DONE
    private LocalDateTime deadline;
    private Integer progress;
    private Integer deleted;
    private LocalDateTime doneAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
