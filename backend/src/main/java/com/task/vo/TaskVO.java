package com.task.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class TaskVO {
    private Long id;
    private String name;
    private String description;
    private Long creatorId;
    private String creatorName;
    private String assignType;
    private Long assigneeId;
    private String assigneeName;   // 个人=姓名，小组=组名
    private String status;
    private LocalDateTime deadline;
    private Integer progress;
    private LocalDateTime doneAt;
    private LocalDateTime createdAt;
    private List<TaskMemberVO> members;
    private List<AttachmentVO> attachments;
}
