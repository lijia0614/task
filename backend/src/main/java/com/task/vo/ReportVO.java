package com.task.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReportVO {
    private Long id;
    private Long taskMemberId;
    private Long userId;
    private String userName;
    private String content;
    private Integer progress;         // 汇报填写的进度
    private Integer finalProgress;    // 审核最终确定的进度（null=未审核或与汇报一致）
    private String status;            // PENDING / APPROVED / REJECTED
    private Long reviewerId;
    private String reviewerName;
    private String reviewComment;     // 审核内容/意见
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
