package com.task.vo;

import lombok.Data;

@Data
public class ReportSummaryVO {
    private long total;
    private long doing;
    private long done;
    private long overdue;
    private int completionRate;
    private int overdueRate;
}
