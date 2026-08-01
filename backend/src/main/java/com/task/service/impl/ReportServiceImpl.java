package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.dto.ReportRequest;
import com.task.entity.*;
import com.task.enums.ReportStatus;
import com.task.mapper.*;
import com.task.service.ReportService;
import com.task.vo.ReportVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private ReportVO toVO(Report r) {
        ReportVO vo = new ReportVO();
        vo.setId(r.getId());
        vo.setTaskMemberId(r.getTaskMemberId());
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
