package com.task.controller;

import com.task.auth.UserContext;
import com.task.common.Result;
import com.task.dto.ReportRequest;
import com.task.dto.ReviewRequest;
import com.task.service.ReportService;
import com.task.vo.ReportVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReportController {
    private final ReportService reportService;

    @PostMapping("/tasks/{taskId}/reports")
    public Result<Long> submit(@PathVariable Long taskId, @Valid @RequestBody ReportRequest req) {
        return Result.ok(reportService.submit(taskId, req));
    }

    @GetMapping("/tasks/{taskId}/reports")
    public Result<List<ReportVO>> listByTask(@PathVariable Long taskId) {
        return Result.ok(reportService.listByTask(taskId, UserContext.get()));
    }

    @PostMapping("/reports/{id}/approve")
    public Result<Void> approve(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        reportService.approve(id, req);
        return Result.ok();
    }

    @PostMapping("/reports/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        reportService.reject(id, req);
        return Result.ok();
    }

    @GetMapping("/reports/pending")
    public Result<List<ReportVO>> pending() {
        return Result.ok(reportService.pendingList(UserContext.get()));
    }
}
