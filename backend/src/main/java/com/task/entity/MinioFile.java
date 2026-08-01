package com.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("minio_file")
public class MinioFile {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String objectName;
    private String fileName;
    private Long size;
    private Long uploaderId;
    private LocalDateTime createdAt;
}
