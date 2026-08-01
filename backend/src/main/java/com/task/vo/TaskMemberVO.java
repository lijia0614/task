package com.task.vo;

import lombok.Data;

@Data
public class TaskMemberVO {
    private Long id;
    private Long taskId;
    private Long userId;
    private String realName;
    private Integer weight;
    private Integer progress;
}
