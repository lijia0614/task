package com.task.controller;

import com.task.common.Result;
import com.task.service.AdminReportService;
import com.task.vo.ReportSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {
    private final AdminReportService adminReportService;

    @GetMapping("/summary")
    public Result<ReportSummaryVO> summary(@RequestParam(defaultValue = "all") String range) {
        return Result.ok(adminReportService.reportSummary(range));
    }
}
