package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.dto.ReportRequest;
import com.task.dto.ReviewRequest;
import com.task.entity.*;
import com.task.enums.ReportStatus;
import com.task.enums.Role;
import com.task.enums.TaskStatus;
import com.task.mapper.*;
import com.task.service.NotificationService;
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
    private final ReportHistoryMapper historyMapper;
    private final NotificationService notificationService;

    /** 校验：新进度必须 ≥ 当前进度，且 ≤ 100 */
    public static void checkProgressRule(int current, int target) {
        if (target < current) throw new IllegalArgumentException("进度不能低于当前进度");
        if (target > 100) throw new IllegalArgumentException("进度不能超过 100");
    }

    /** 每次成功操作后，在同一事务记录内容与进度快照；APPROVED 记 finalProgress，其余记汇报请求进度 */
    private void recordHistory(Report r, String action, Integer snapshotProgress) {
        ReportHistory h = new ReportHistory();
        h.setReportId(r.getId());
        h.setAction(action);
        h.setContent(r.getContent());
        h.setProgress(snapshotProgress != null ? snapshotProgress : r.getProgress());
        h.setActorId(UserContext.get().getId());
        historyMapper.insert(h);
    }

    /** 锁序与删除任务一致（task → member）：先锁 task 行校验存在/未删除，再锁成员行 */
    @Override
    @Transactional
    public Long submit(Long taskId, ReportRequest req) {
        SysUser current = UserContext.get();
        Task task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null || (task.getDeleted() != null && task.getDeleted() == 1)) {
            throw new BusinessException("任务不存在");
        }
        TaskMember member = memberMapper.selectByTaskAndUserForUpdate(taskId, current.getId());
        if (member == null) throw new BusinessException("你不是该任务的成员");
        // 同一成员同一时间最多一条 PENDING
        if (hasPending(member.getId())) {
            throw new BusinessException("存在待审核的汇报，请先等待审核或撤回");
        }
        try {
            checkProgressRule(member.getProgress(), req.getProgress());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        Report r = new Report();
        r.setTaskMemberId(member.getId());
        r.setUserId(current.getId());
        r.setContent(req.getContent());
        r.setProgress(req.getProgress());
        r.setStatus(ReportStatus.PENDING);
        reportMapper.insert(r);
        recordHistory(r, "SUBMITTED", null);
        // 站内信：有汇报待审核（创建者=提交人时跳过）
        notificationService.notifyReportSubmitted(task, current);
        return r.getId();
    }

    private boolean hasPending(Long taskMemberId) {
        return reportMapper.selectCount(new LambdaQueryWrapper<Report>()
                .eq(Report::getTaskMemberId, taskMemberId)
                .eq(Report::getStatus, ReportStatus.PENDING)) > 0;
    }

    /** 可见性：本人或审核人见全部（WITHDRAWN 除外——仅提交人可见）；其他人仅见 APPROVED */
    @Override
    public List<ReportVO> listByTask(Long taskId, SysUser current) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) throw new BusinessException("任务不存在");
        boolean isReviewer = current.getRole() == Role.ADMIN
                || task.getCreatorId().equals(current.getId());

        List<TaskMember> members = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, taskId));
        List<Long> memberIds = members.stream().map(TaskMember::getId).collect(Collectors.toList());
        if (memberIds.isEmpty()) return List.of();
        List<Report> reports = reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .in(Report::getTaskMemberId, memberIds)
                .orderByDesc(Report::getId));

        return reports.stream()
                .filter(r -> {
                    // WITHDRAWN 仅提交人可见，审核人/其他成员一律不可见
                    if (r.getStatus() == ReportStatus.WITHDRAWN && !r.getUserId().equals(current.getId())) {
                        return false;
                    }
                    return isReviewer || r.getStatus() == ReportStatus.APPROVED
                            || r.getUserId().equals(current.getId());
                })
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
        Report report = reportMapper.selectByIdForUpdate(reportId);
        if (report == null) throw new BusinessException("汇报不存在");
        TaskMember member = memberMapper.selectById(report.getTaskMemberId());
        if (member == null) throw new BusinessException("汇报数据异常");
        // 锁序 report → task → member（与 resubmit 一致）：先显式锁 task 再锁 member。
        // 若按"UPDATE member 后再 UPDATE task"取写锁，会与 resubmit 的"先锁 task 再锁 member"
        // 在相同 task/member 上交叉等待形成死锁（InnoDB 回滚 → 500）
        Task task = taskMapper.selectByIdForUpdate(member.getTaskId());
        if (task == null) throw new BusinessException("任务不存在");
        TaskMember lockedMember = memberMapper.selectByIdForUpdate(member.getId());
        SysUser reviewer = UserContext.get();
        if (reviewer.getRole() != Role.ADMIN && !task.getCreatorId().equals(reviewer.getId())) {
            throw new BusinessException(403, "无权审核该汇报");
        }
        if (report.getStatus() != ReportStatus.PENDING) {
            throw new BusinessException("汇报状态已变化，请刷新");
        }

        int finalProgress = req.getProgress() != null ? req.getProgress() : report.getProgress();
        if (finalProgress > 100) throw new BusinessException("最终进度不能超过 100");
        if (finalProgress < lockedMember.getProgress()) throw new BusinessException("最终进度不能低于当前进度");

        report.setStatus(ReportStatus.APPROVED);
        report.setFinalProgress(finalProgress);
        report.setReviewerId(reviewer.getId());
        report.setReviewComment(req.getReviewComment());
        report.setReviewedAt(LocalDateTime.now());
        reportMapper.updateById(report);
        recordHistory(report, "APPROVED", finalProgress);

        lockedMember.setProgress(finalProgress);
        memberMapper.updateById(lockedMember);

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
        // 站内信：汇报已通过（提交人=审核人时跳过）
        notificationService.notifyReportReviewed(task, report, reviewer, true, finalProgress, req.getReviewComment());
    }

    @Override
    @Transactional
    public void reject(Long reportId, ReviewRequest req) {
        // 先加载数据并校验权限，再做状态/参数校验：未授权用户不得通过错误文案获知汇报状态
        Report report = reportMapper.selectByIdForUpdate(reportId);
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
        if (report.getStatus() != ReportStatus.PENDING) {
            throw new BusinessException("汇报状态已变化，请刷新");
        }

        report.setStatus(ReportStatus.REJECTED);
        report.setReviewerId(reviewer.getId());
        report.setReviewComment(req.getReviewComment());
        report.setReviewedAt(LocalDateTime.now());
        reportMapper.updateById(report);
        recordHistory(report, "REJECTED", null);
        // 站内信：汇报被驳回（提交人=审核人时跳过）
        notificationService.notifyReportReviewed(task, report, reviewer, false, null, req.getReviewComment());
    }

    /** PENDING → WITHDRAWN；仅提交人；状态变化并发时只有一个成功 */
    @Override
    @Transactional
    public void withdraw(Long reportId) {
        Report report = reportMapper.selectByIdForUpdate(reportId);
        if (report == null) throw new BusinessException("汇报不存在");
        if (!report.getUserId().equals(UserContext.get().getId())) {
            throw new BusinessException(403, "无权操作该汇报");
        }
        if (report.getStatus() != ReportStatus.PENDING) {
            throw new BusinessException("汇报状态已变化，请刷新");
        }
        report.setStatus(ReportStatus.WITHDRAWN);
        reportMapper.updateById(report);
        recordHistory(report, "WITHDRAWN", null);
    }

    /** 仅 WITHDRAWN 可编辑；目标进度不低于成员当前进度且 ≤ 100 */
    @Override
    @Transactional
    public void update(Long reportId, ReportRequest req) {
        Report report = reportMapper.selectByIdForUpdate(reportId);
        if (report == null) throw new BusinessException("汇报不存在");
        if (!report.getUserId().equals(UserContext.get().getId())) {
            throw new BusinessException(403, "无权操作该汇报");
        }
        if (report.getStatus() != ReportStatus.WITHDRAWN) {
            throw new BusinessException("汇报状态已变化，请刷新");
        }
        TaskMember member = memberMapper.selectById(report.getTaskMemberId());
        try {
            checkProgressRule(member.getProgress(), req.getProgress());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        report.setContent(req.getContent());
        report.setProgress(req.getProgress());
        reportMapper.updateById(report);
        recordHistory(report, "EDITED", null);
    }

    /**
     * WITHDRAWN → PENDING；清空旧审核字段；锁成员行保证单待审核。
     * 锁顺序 report → task → member：task 锁先于 member 锁，与 delete/submit 的
     * task → member 序一致，绝不在持有 member 锁后再等待 task 锁（否则与 delete 成环）。
     * 重新校验所属任务未删除（已删除 → 「任务不存在」）与当前汇报进度
     * （不低于成员当前进度且 ≤ 100），任一失败保持 WITHDRAWN 且不写历史。
     */
    @Override
    @Transactional
    public void resubmit(Long reportId) {
        Report report = reportMapper.selectByIdForUpdate(reportId);
        if (report == null) throw new BusinessException("汇报不存在");
        if (!report.getUserId().equals(UserContext.get().getId())) {
            throw new BusinessException(403, "无权操作该汇报");
        }
        if (report.getStatus() != ReportStatus.WITHDRAWN) {
            throw new BusinessException("汇报状态已变化，请刷新");
        }
        TaskMember member = memberMapper.selectById(report.getTaskMemberId());
        if (member == null) throw new BusinessException("任务不存在");
        // 先锁 task（校验未删除），再锁 member —— 与 delete/submit 的 task → member 锁序一致
        Task task = taskMapper.selectByIdForUpdate(member.getTaskId());
        if (task == null || (task.getDeleted() != null && task.getDeleted() == 1)) {
            throw new BusinessException("任务不存在");
        }
        TaskMember lockedMember = memberMapper.selectByIdForUpdate(member.getId());
        // 重提必须按规格重新校验当前汇报进度
        try {
            checkProgressRule(lockedMember.getProgress(), report.getProgress());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
        if (hasPending(lockedMember.getId())) {
            throw new BusinessException("存在待审核的汇报，请先等待审核或撤回");
        }
        // updateById 不更新 null 字段，清空审核字段必须显式 SET NULL
        reportMapper.update(null, new LambdaUpdateWrapper<Report>()
                .eq(Report::getId, report.getId())
                .set(Report::getStatus, ReportStatus.PENDING)
                .set(Report::getReviewerId, null)
                .set(Report::getReviewComment, null)
                .set(Report::getReviewedAt, null)
                .set(Report::getFinalProgress, null));
        report.setStatus(ReportStatus.PENDING);
        report.setReviewerId(null);
        report.setReviewComment(null);
        report.setReviewedAt(null);
        report.setFinalProgress(null);
        recordHistory(report, "RESUBMITTED", null);
        // 站内信：重提后再次通知审核人
        notificationService.notifyReportSubmitted(task, UserContext.get());
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
