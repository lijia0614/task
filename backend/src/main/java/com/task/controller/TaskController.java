package com.task.controller;

import com.task.auth.UserContext;
import com.task.common.Result;
import com.task.dto.CreateTaskRequest;
import com.task.service.TaskService;
import com.task.vo.TaskVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;

    @PostMapping
    public Result<Long> create(@Valid @RequestBody CreateTaskRequest req) {
        return Result.ok(taskService.create(req));
    }

    @GetMapping
    public Result<List<TaskVO>> list(@RequestParam(required = false) String type,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String keyword) {
        return Result.ok(taskService.list(type, status, keyword, UserContext.get()));
    }

    @GetMapping("/{id}")
    public Result<TaskVO> detail(@PathVariable Long id) {
        return Result.ok(taskService.detail(id));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody CreateTaskRequest req) {
        taskService.update(id, req, UserContext.get());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        taskService.delete(id, UserContext.get());
        return Result.ok();
    }

    @PutMapping("/{id}/weights")
    public Result<Void> updateWeights(@PathVariable Long id, @RequestBody List<Map<String, Integer>> weights) {
        taskService.updateWeights(id, weights, UserContext.get());
        return Result.ok();
    }
}
