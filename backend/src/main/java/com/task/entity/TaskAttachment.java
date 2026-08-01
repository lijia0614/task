package com.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_attachment")
public class TaskAttachment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private Long uploadedBy;
    private LocalDateTime createdAt;
}
