package com.task.vo;

import lombok.Data;

@Data
public class AttachmentVO {
    private Long id;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
}
