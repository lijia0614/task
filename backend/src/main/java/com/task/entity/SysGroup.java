package com.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_group")
public class SysGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long leaderId;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
