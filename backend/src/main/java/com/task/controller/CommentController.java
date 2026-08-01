package com.task.controller;

import com.task.common.Result;
import com.task.dto.CommentRequest;
import com.task.service.CommentService;
import com.task.vo.CommentVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CommentController {
    private final CommentService commentService;

    @GetMapping("/tasks/{taskId}/comments")
    public Result<List<CommentVO>> taskComments(@PathVariable Long taskId) {
        return Result.ok(commentService.taskComments(taskId));
    }

    @PostMapping("/tasks/{taskId}/comments")
    public Result<Long> addTaskComment(@PathVariable Long taskId, @Valid @RequestBody CommentRequest req) {
        return Result.ok(commentService.addTaskComment(taskId, req.getContent()));
    }

    @GetMapping("/reports/{reportId}/comments")
    public Result<List<CommentVO>> reportComments(@PathVariable Long reportId) {
        return Result.ok(commentService.reportComments(reportId));
    }

    @PostMapping("/reports/{reportId}/comments")
    public Result<Long> addReportComment(@PathVariable Long reportId, @Valid @RequestBody CommentRequest req) {
        return Result.ok(commentService.addReportComment(reportId, req.getContent()));
    }

    @PostMapping("/comments/{id}/reply")
    public Result<Long> reply(@PathVariable Long id, @Valid @RequestBody CommentRequest req) {
        return Result.ok(commentService.reply(id, req.getContent()));
    }
}
