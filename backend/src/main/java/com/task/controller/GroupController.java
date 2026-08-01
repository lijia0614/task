package com.task.controller;

import com.task.common.Result;
import com.task.dto.GroupRequest;
import com.task.dto.MemberRequest;
import com.task.service.GroupService;
import com.task.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {
    private final GroupService groupService;

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(groupService.list());
    }

    @GetMapping("/{id}/members")
    public Result<List<UserVO>> members(@PathVariable Long id) {
        return Result.ok(groupService.members(id));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody GroupRequest req) {
        return Result.ok(groupService.create(req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody GroupRequest req) {
        groupService.update(id, req);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        groupService.delete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/members")
    public Result<Void> addMember(@PathVariable Long id, @Valid @RequestBody MemberRequest req) {
        groupService.addMember(id, req.getUserId());
        return Result.ok();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        groupService.removeMember(id, userId);
        return Result.ok();
    }
}
