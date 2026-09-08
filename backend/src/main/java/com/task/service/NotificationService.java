package com.task.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.entity.Report;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.vo.NotificationVO;

import java.util.List;

public interface NotificationService {
    Page<NotificationVO> list(long page, long size);

    long unreadCount();

    void markRead(Long id);

    void markAllRead();

    /** 任务创建成功后：通知除创建者外的全部成员 */
    void notifyTaskAssigned(Task task, SysUser creator, List<SysUser> assignees);

    /** 汇报提交/重提成功后：通知任务创建者（创建者=提交人则跳过） */
    void notifyReportSubmitted(Task task, SysUser submitter);

    /** 审核通过/驳回后：通知汇报提交人（提交人=审核人则跳过） */
    void notifyReportReviewed(Task task, Report report, SysUser reviewer, boolean approved, Integer finalProgress, String reviewComment);
}
