package com.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 汇报状态变化审计记录 */
@Data
@TableName("report_history")
public class ReportHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reportId;
    /** SUBMITTED / WITHDRAWN / EDITED / RESUBMITTED / APPROVED / REJECTED */
    private String action;
    private String content;
    private Integer progress;
    private Long actorId;
    private LocalDateTime createdAt;
}
