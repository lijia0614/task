package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.dto.CreateTaskRequest;
import com.task.entity.*;
import com.task.mapper.*;
import com.task.service.TaskService;
import com.task.vo.AttachmentVO;
import com.task.vo.TaskMemberVO;
import com.task.vo.TaskVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {
    private final TaskMapper taskMapper;
    private final TaskMemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final TaskAttachmentMapper attachmentMapper;
    private final MinioFileMapper minioFileMapper;
    private final ReportMapper reportMapper;

    @Value("${minio.public-url}")
    private String minioPublicUrl;
    @Value("${minio.bucket}")
    private String minioBucket;

    /** 均分权重：100/n，余数依次给前几个成员（n=3 → 34,33,33） */
    public static List<Integer> splitWeights(int n) {
        List<Integer> list = new ArrayList<>();
        int base = 100 / n;
        int rest = 100 % n;
        for (int i = 0; i < n; i++) list.add(base + (i < rest ? 1 : 0));
        return list;
    }

    /** 整体进度 = Σ(进度×权重)/Σ权重，四舍五入 */
    public static int calcOverallProgress(List<Integer> weights, List<Integer> progresses) {
        long sumW = weights.stream().mapToLong(Integer::longValue).sum();
        long sumP = 0;
        for (int i = 0; i < weights.size(); i++) {
            sumP += (long) progresses.get(i) * weights.get(i);
        }
        return sumW == 0 ? 0 : (int) Math.round((double) sumP / sumW);
    }

    private boolean canManage(Task t, SysUser cur) {
        return "ADMIN".equals(cur.getRole()) || t.getCreatorId().equals(cur.getId());
    }

    @Override
    @Transactional
    public Long create(CreateTaskRequest req) {
        SysUser creator = UserContext.get();
        if (!"ADMIN".equals(creator.getRole()) && !"LEADER".equals(creator.getRole())) {
            throw new BusinessException(403, "无权创建任务");
        }
        Task task = new Task();
        task.setName(req.getName());
        task.setDescription(req.getDescription());
        task.setCreatorId(creator.getId());
        task.setAssigneeId(req.getAssigneeId());
        task.setDeadline(req.getDeadline());
        task.setStatus("DOING");
        task.setProgress(0);

        List<SysUser> assignees;
        if ("INDIVIDUAL".equals(req.getAssignType())) {
            SysUser u = userMapper.selectById(req.getAssigneeId());
            if (u == null) throw new BusinessException("用户不存在");
            task.setAssignType("INDIVIDUAL");
            assignees = List.of(u);
        } else if ("GROUP".equals(req.getAssignType())) {
            SysGroup g = groupMapper.selectById(req.getAssigneeId());
            if (g == null) throw new BusinessException("小组不存在");
            task.setAssignType("GROUP");
            assignees = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getGroupId, g.getId()));
            if (assignees.isEmpty()) throw new BusinessException("小组没有成员");
        } else {
            throw new BusinessException("分配类型不合法");
        }
        taskMapper.insert(task);

        List<Integer> weights = req.getWeights() != null && req.getWeights().size() == assignees.size()
                ? req.getWeights() : splitWeights(assignees.size());
        for (int i = 0; i < assignees.size(); i++) {
            TaskMember m = new TaskMember();
            m.setTaskId(task.getId());
            m.setUserId(assignees.get(i).getId());
            m.setWeight(weights.get(i));
            m.setProgress(0);
            memberMapper.insert(m);
        }
        // 关联附件（minio_file id → task_attachment）
        if (req.getAttachmentIds() != null) {
            for (Long fileId : req.getAttachmentIds()) {
                MinioFile f = minioFileMapper.selectById(fileId);
                if (f == null) continue;
                TaskAttachment a = new TaskAttachment();
                a.setTaskId(task.getId());
                a.setFileName(f.getFileName());
                a.setFileUrl(minioPublicUrl + "/" + minioBucket + "/" + f.getObjectName());
                a.setFileSize(f.getSize());
                a.setUploadedBy(creator.getId());
                attachmentMapper.insert(a);
            }
        }
        return task.getId();
    }

    @Override
    public List<TaskVO> list(String type, String status, String keyword, SysUser cur) {
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
            if (myTaskIds.isEmpty()) return Collections.emptyList();
            qw.in(Task::getId, myTaskIds);
        }
        return taskMapper.selectList(qw).stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public TaskVO detail(Long id) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        return toVO(t);
    }

    @Override
    public void update(Long id, CreateTaskRequest req, SysUser cur) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        if (!canManage(t, cur)) throw new BusinessException(403, "无权修改任务");
        t.setName(req.getName());
        t.setDescription(req.getDescription());
        t.setDeadline(req.getDeadline());
        taskMapper.updateById(t);
    }

    @Override
    public void delete(Long id, SysUser cur) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        if (!canManage(t, cur)) throw new BusinessException(403, "无权删除任务");
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
    }

    @Override
    public void updateWeights(Long id, List<Map<String, Integer>> weights, SysUser cur) {
        Task t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException("任务不存在");
        if (!canManage(t, cur)) throw new BusinessException(403, "无权操作");
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
