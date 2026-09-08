package com.task.service;

import com.task.vo.ReportSummaryVO;

public interface AdminReportService {
    /** 管理员总览统计（四分类互斥；时间范围按任务创建时间过滤） */
    ReportSummaryVO reportSummary(String range);
}
