package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.entity.Notification;
import com.task.entity.Report;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.mapper.NotificationMapper;
import com.task.service.NotificationService;
import com.task.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final NotificationMapper notificationMapper;

    @Override
    public Page<NotificationVO> list(long page, long size) {
        if (page < 1 || size < 1 || size > 1000) throw new BusinessException("分页参数不合法");
        Long uid = UserContext.get().getId();
        Page<Notification> p = notificationMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, uid)
                        .orderByDesc(Notification::getId));
        Page<NotificationVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(p.getRecords().stream().map(NotificationVO::from).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public long unreadCount() {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, UserContext.get().getId())
                .eq(Notification::getIsRead, 0));
    }

    @Override
    public void markRead(Long id) {
        Notification n = notificationMapper.selectById(id);
        // 不存在或非本人一律 404，不泄露他人通知的存在性
        if (n == null || !n.getUserId().equals(UserContext.get().getId())) {
            throw new BusinessException(404, "通知不存在");
        }
        if (n.getIsRead() != 1) {
            n.setIsRead(1);
            notificationMapper.updateById(n);
        }
    }

    @Override
    public void markAllRead() {
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, UserContext.get().getId())
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1));
    }

    @Override
    public void notifyTaskAssigned(Task task, SysUser creator, List<SysUser> assignees) {
        for (SysUser u : assignees) {
            if (u.getId().equals(creator.getId())) continue; // 创建者自己也是成员时不通知本人
            insert(u.getId(), "TASK_ASSIGNED", "新任务分配", "你被分配了任务「" + task.getName() + "」", task.getId(), null);
        }
    }

    @Override
    public void notifyReportSubmitted(Task task, SysUser submitter) {
        if (task.getCreatorId().equals(submitter.getId())) return;
        insert(task.getCreatorId(), "REPORT_SUBMITTED", "待审核汇报",
                "任务「" + task.getName() + "」有新的汇报待你审核", task.getId(), null);
    }

    @Override
    public void notifyReportReviewed(Task task, Report report, SysUser reviewer, boolean approved, Integer finalProgress, String reviewComment) {
        if (reviewer.getId().equals(report.getUserId())) return;
        if (approved) {
            insert(report.getUserId(), "REPORT_APPROVED", "汇报已通过",
                    "你的汇报（任务「" + task.getName() + "」）已通过，最终进度 " + finalProgress + "%",
                    task.getId(), report.getId());
        } else {
            insert(report.getUserId(), "REPORT_REJECTED", "汇报被驳回",
                    "你的汇报（任务「" + task.getName() + "」）被驳回：" + reviewComment,
                    task.getId(), report.getId());
        }
    }

    private void insert(Long userId, String type, String title, String content, Long taskId, Long reportId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setTaskId(taskId);
        n.setReportId(reportId);
        n.setIsRead(0);
        notificationMapper.insert(n);
    }
}
