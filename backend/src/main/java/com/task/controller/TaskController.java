package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.common.Result;
import com.task.dto.CreateTaskRequest;
import com.task.entity.*;
import com.task.mapper.*;
import com.task.service.TaskService;
import com.task.vo.AttachmentVO;
import com.task.vo.TaskMemberVO;
import com.task.vo.TaskVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService taskService;
    private final TaskMapper taskMapper;
    private final TaskMemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final TaskAttachmentMapper attachmentMapper;
    private final MinioFileMapper minioFileMapper;
    private final ReportMapper reportMapper;

    private SysUser current() { return UserContext.get(); }

    private boolean canManage(Task t) {
        SysUser cur = current();
        return "ADMIN".equals(cur.getRole()) || t.getCreatorId().equals(cur.getId());
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody CreateTaskRequest req) {
        if (!"ADMIN".equals(current().getRole()) && !"LEADER".equals(current().getRole())) {
            throw new BusinessException(403, "无权创建任务");
        }
        Long taskId = taskService.create(req);
        // 关联附件（minio_file id → task_attachment）
        if (req.getAttachmentIds() != null) {
            for (Long fileId : req.getAttachmentIds()) {
                MinioFile f = minioFileMapper.selectById(fileId);
                if (f == null) continue;
                TaskAttachment a = new TaskAttachment();
                a.setTaskId(taskId);
                a.setFileName(f.getFileName());
                a.setFileUrl("http://127.0.0.1:9000/task-attachments/" + f.getObjectName());
                a.setFileSize(f.getSize());
                a.setUploadedBy(current().getId());
                attachmentMapper.insert(a);
            }
        }
        return Result.ok(taskId);
    }

    @GetMapping
    public Result<List<TaskVO>> list(@RequestParam(required = false) String type,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String keyword) {
        SysUser cur = current();
        List<Long> myTaskIds = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                        .eq(TaskMember::getUserId, cur.getId()))
                .stream().map(TaskMember::getTaskId).collect(Collectors.toList());

        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<Task>()
                .eq(StringUtils.hasText(status), Task::getStatus, status)
                .like(StringUtils.hasText(keyword), Task::getName, keyword)
                .orderByDesc(Task::getId);
        if ("mine_created".equals(type)) {
            qw.eq(Task::getCreatorId, cur.getId());
        } else if ("assigned".equals(type)) {
            if (myTaskIds.isEmpty()) return Result.ok(Collections.emptyList());
            qw.in(Task::getId, myTaskIds);
        }
        return Result.ok(taskMapper.selectList(qw).stream().map(this::toVO).collect(Collectors.toList()));
    }

    @GetMapping("/{id}")
    public Result<TaskVO> detail(@PathVariable Long id) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        return Result.ok(toVO(t));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody CreateTaskRequest req) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        if (!canManage(t)) throw new BusinessException(403, "无权修改任务");
        t.setName(req.getName());
        t.setDescription(req.getDescription());
        t.setDeadline(req.getDeadline());
        taskMapper.updateById(t);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        if (!canManage(t)) throw new BusinessException(403, "无权删除任务");
        List<Long> memberIds = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                        .eq(TaskMember::getTaskId, id))
                .stream().map(TaskMember::getId).collect(Collectors.toList());
        if (!memberIds.isEmpty()) {
            Long pending = reportMapper.selectCount(new LambdaQueryWrapper<Report>()
                    .in(Report::getTaskMemberId, memberIds)
                    .eq(Report::getStatus, "PENDING"));
            if (pending > 0) throw new BusinessException("存在待审核汇报，无法删除");
        }
        taskMapper.deleteById(id); // 逻辑删除
        return Result.ok();
    }

    @PutMapping("/{id}/weights")
    public Result<Void> updateWeights(@PathVariable Long id, @RequestBody List<Map<String, Integer>> weights) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        if (!canManage(t)) throw new BusinessException(403, "无权操作");
        List<TaskMember> members = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, id));
        if (weights.size() != members.size()) throw new BusinessException("权重数量与成员数不一致");
        // 先校验全部权重，再统一写入，避免部分更新
        Map<Long, Integer> newWeights = new HashMap<>();
        for (TaskMember m : members) {
            Map<String, Integer> w = weights.stream()
                    .filter(x -> x.get("userId") != null && x.get("userId").equals(m.getUserId().intValue()))
                    .findFirst().orElseThrow(() -> new BusinessException("权重缺少成员 " + m.getUserId()));
            Integer weight = w.get("weight");
            if (weight == null || weight <= 0 || weight > 100) throw new BusinessException("权重不合法");
            newWeights.put(m.getUserId(), weight);
        }
        int sum = newWeights.values().stream().mapToInt(Integer::intValue).sum();
        if (sum != 100) throw new BusinessException("权重之和必须为 100");
        for (TaskMember m : members) {
            m.setWeight(newWeights.get(m.getUserId()));
            memberMapper.updateById(m);
        }
        return Result.ok();
    }

    private TaskVO toVO(Task t) {
        TaskVO vo = new TaskVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setDescription(t.getDescription());
        vo.setCreatorId(t.getCreatorId());
        SysUser creator = userMapper.selectById(t.getCreatorId());
        vo.setCreatorName(creator == null ? null : creator.getRealName());
        vo.setAssignType(t.getAssignType());
        vo.setAssigneeId(t.getAssigneeId());
        if ("INDIVIDUAL".equals(t.getAssignType())) {
            SysUser u = userMapper.selectById(t.getAssigneeId());
            vo.setAssigneeName(u == null ? null : u.getRealName());
        } else {
            SysGroup g = groupMapper.selectById(t.getAssigneeId());
            vo.setAssigneeName(g == null ? null : g.getName());
        }
        vo.setStatus(t.getStatus());
        vo.setDeadline(t.getDeadline());
        vo.setProgress(t.getProgress());
        vo.setDoneAt(t.getDoneAt());
        vo.setCreatedAt(t.getCreatedAt());

        List<TaskMember> members = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, t.getId()));
        vo.setMembers(members.stream().map(m -> {
            TaskMemberVO mv = new TaskMemberVO();
            mv.setId(m.getId());
            mv.setTaskId(m.getTaskId());
            mv.setUserId(m.getUserId());
            SysUser u = userMapper.selectById(m.getUserId());
            mv.setRealName(u == null ? null : u.getRealName());
            mv.setWeight(m.getWeight());
            mv.setProgress(m.getProgress());
            return mv;
        }).collect(Collectors.toList()));

        vo.setAttachments(attachmentMapper.selectList(new LambdaQueryWrapper<TaskAttachment>()
                        .eq(TaskAttachment::getTaskId, t.getId()))
                .stream().map(a -> {
                    AttachmentVO av = new AttachmentVO();
                    av.setId(a.getId());
                    av.setFileName(a.getFileName());
                    av.setFileUrl(a.getFileUrl());
                    av.setFileSize(a.getFileSize());
                    return av;
                }).collect(Collectors.toList()));
        return vo;
    }
}
