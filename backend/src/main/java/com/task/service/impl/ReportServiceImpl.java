package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.dto.ReportRequest;
import com.task.dto.ReviewRequest;
import com.task.entity.*;
import com.task.enums.ReportStatus;
import com.task.enums.Role;
import com.task.enums.TaskStatus;
import com.task.mapper.*;
import com.task.service.ReportService;
import com.task.vo.ReportVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {
    private final ReportMapper reportMapper;
    private final TaskMemberMapper memberMapper;
    private final TaskMapper taskMapper;
    private final SysUserMapper userMapper;

    /** 校验：新进度必须 ≥ 当前进度，且 ≤ 100 */
    public static void checkProgressRule(int current, int target) {
        if (target < current) throw new IllegalArgumentException("进度不能低于当前进度");
        if (target > 100) throw new IllegalArgumentException("进度不能超过 100");
    }

    @Override
    @Transactional
    public Long submit(Long taskId, ReportRequest req) {
        TaskMember member = memberMapper.selectOne(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, taskId)
                .eq(TaskMember::getUserId, UserContext.get().getId()));
        if (member == null) throw new BusinessException("你不是该任务的成员");
        try {
            checkProgressRule(member.getProgress(), req.getProgress());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        Report r = new Report();
        r.setTaskMemberId(member.getId());
        r.setUserId(UserContext.get().getId());
        r.setContent(req.getContent());
        r.setProgress(req.getProgress());
        r.setStatus(ReportStatus.PENDING);
        reportMapper.insert(r);
        return r.getId();
    }

    /** 可见性：本人或审核人见全部；其他人仅见 APPROVED */
    @Override
    public List<ReportVO> listByTask(Long taskId, SysUser current) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) throw new BusinessException("任务不存在");
        boolean isReviewer = current.getRole() == com.task.enums.Role.ADMIN
                || task.getCreatorId().equals(current.getId());

        List<TaskMember> members = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, taskId));
        List<Long> memberIds = members.stream().map(TaskMember::getId).collect(Collectors.toList());
        if (memberIds.isEmpty()) return List.of();
        List<Report> reports = reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .in(Report::getTaskMemberId, memberIds)
                .orderByDesc(Report::getId));

        return reports.stream()
                .filter(r -> isReviewer || r.getStatus() == ReportStatus.APPROVED
                        || r.getUserId().equals(current.getId()))
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    /** 是否所有成员进度都达到 100 */
    public static boolean isAllCompleted(List<Integer> progresses) {
        return !progresses.isEmpty() && progresses.stream().allMatch(p -> p >= 100);
    }

    @Override
    @Transactional
    public void approve(Long reportId, ReviewRequest req) {
        // 先加载数据并校验权限，再做状态/参数校验：未授权用户不得通过错误文案获知汇报状态
        Report report = reportMapper.selectById(reportId);
        if (report == null) throw new BusinessException("汇报不存在");
        TaskMember member = memberMapper.selectById(report.getTaskMemberId());
        Task task = taskMapper.selectById(member.getTaskId());
        SysUser reviewer = UserContext.get();
        if (reviewer.getRole() != Role.ADMIN && !task.getCreatorId().equals(reviewer.getId())) {
            throw new BusinessException(403, "无权审核该汇报");
        }
        if (report.getStatus() != ReportStatus.PENDING) throw new BusinessException("该汇报已审核");

        int finalProgress = req.getProgress() != null ? req.getProgress() : report.getProgress();
        if (finalProgress > 100) throw new BusinessException("最终进度不能超过 100");
        if (finalProgress < member.getProgress()) throw new BusinessException("最终进度不能低于当前进度");

        report.setStatus(ReportStatus.APPROVED);
        report.setFinalProgress(finalProgress);
        report.setReviewerId(reviewer.getId());
        report.setReviewComment(req.getReviewComment());
        report.setReviewedAt(LocalDateTime.now());
        reportMapper.updateById(report);

        member.setProgress(finalProgress);
        memberMapper.updateById(member);

        // 重算整体进度与完成状态
        List<TaskMember> members = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, task.getId()));
        List<Integer> weights = members.stream().map(TaskMember::getWeight).collect(Collectors.toList());
        List<Integer> progresses = members.stream().map(TaskMember::getProgress).collect(Collectors.toList());
        task.setProgress(TaskServiceImpl.calcOverallProgress(weights, progresses));
        if (isAllCompleted(progresses)) {
            task.setStatus(TaskStatus.DONE);
            task.setDoneAt(LocalDateTime.now());
        }
        taskMapper.updateById(task);
    }

    @Override
    @Transactional
    public void reject(Long reportId, ReviewRequest req) {
        // 先加载数据并校验权限，再做状态/参数校验：未授权用户不得通过错误文案获知汇报状态
        Report report = reportMapper.selectById(reportId);
        if (report == null) throw new BusinessException("汇报不存在");
        TaskMember member = memberMapper.selectById(report.getTaskMemberId());
        Task task = taskMapper.selectById(member.getTaskId());
        SysUser reviewer = UserContext.get();
        if (reviewer.getRole() != Role.ADMIN && !task.getCreatorId().equals(reviewer.getId())) {
            throw new BusinessException(403, "无权审核该汇报");
        }
        if (req.getReviewComment() == null || req.getReviewComment().isBlank()) {
            throw new BusinessException("驳回必须填写审核内容（不通过的理由）");
        }
        if (report.getStatus() != ReportStatus.PENDING) throw new BusinessException("该汇报已审核");

        report.setStatus(ReportStatus.REJECTED);
        report.setReviewerId(reviewer.getId());
        report.setReviewComment(req.getReviewComment());
        report.setReviewedAt(LocalDateTime.now());
        reportMapper.updateById(report);
    }

    @Override
    public List<ReportVO> pendingList(SysUser current) {
        LambdaQueryWrapper<Task> taskQw = current.getRole() == Role.ADMIN
                ? new LambdaQueryWrapper<Task>()
                : new LambdaQueryWrapper<Task>().eq(Task::getCreatorId, current.getId());
        List<Long> taskIds = taskMapper.selectList(taskQw).stream().map(Task::getId).collect(Collectors.toList());
        if (taskIds.isEmpty()) return List.of();
        List<Long> memberIds = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                        .in(TaskMember::getTaskId, taskIds))
                .stream().map(TaskMember::getId).collect(Collectors.toList());
        if (memberIds.isEmpty()) return List.of();
        return reportMapper.selectList(new LambdaQueryWrapper<Report>()
                        .in(Report::getTaskMemberId, memberIds)
                        .eq(Report::getStatus, ReportStatus.PENDING)
                        .orderByAsc(Report::getId))
                .stream().map(this::toVO).collect(Collectors.toList());
    }

    private ReportVO toVO(Report r) {
        ReportVO vo = new ReportVO();
        vo.setId(r.getId());
        vo.setTaskMemberId(r.getTaskMemberId());
        TaskMember m = memberMapper.selectById(r.getTaskMemberId());
        if (m != null) {
            vo.setTaskId(m.getTaskId());
            Task t = taskMapper.selectById(m.getTaskId());
            vo.setTaskName(t == null ? null : t.getName());
        }
        vo.setUserId(r.getUserId());
        SysUser u = userMapper.selectById(r.getUserId());
        vo.setUserName(u == null ? null : u.getRealName());
        vo.setContent(r.getContent());
        vo.setProgress(r.getProgress());
        vo.setFinalProgress(r.getFinalProgress());
        vo.setStatus(r.getStatus() == null ? null : r.getStatus().name());
        vo.setReviewerId(r.getReviewerId());
        if (r.getReviewerId() != null) {
            SysUser reviewer = userMapper.selectById(r.getReviewerId());
            vo.setReviewerName(reviewer == null ? null : reviewer.getRealName());
        }
        vo.setReviewComment(r.getReviewComment());
        vo.setReviewedAt(r.getReviewedAt());
        vo.setCreatedAt(r.getCreatedAt());
        return vo;
    }
}
