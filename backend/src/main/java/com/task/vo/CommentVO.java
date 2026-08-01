package com.task.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CommentVO {
    private Long id;
    private Long parentId;
    private Long userId;
    private String userName;
    private String content;
    private LocalDateTime createdAt;
}
