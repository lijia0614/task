package com.task.controller;

import com.task.auth.UserContext;
import com.task.common.Result;
import com.task.dto.ReportRequest;
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
}
