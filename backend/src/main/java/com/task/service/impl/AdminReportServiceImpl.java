package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.entity.Task;
import com.task.enums.Role;
import com.task.enums.TaskStatus;
import com.task.mapper.TaskMapper;
import com.task.service.AdminReportService;
import com.task.vo.ReportSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminReportServiceImpl implements AdminReportService {
    private final TaskMapper taskMapper;

    @Override
    public ReportSummaryVO reportSummary(String range) {
        if (UserContext.get().getRole() != Role.ADMIN) throw new BusinessException(403, "无权操作");
        LocalDateTime from;
        if (range == null || "all".equals(range)) {
            from = null;
        } else if ("7d".equals(range)) {
            from = LocalDateTime.now().minusDays(7);
        } else if ("30d".equals(range)) {
            from = LocalDateTime.now().minusDays(30);
        } else {
            throw new BusinessException("时间范围不合法");
        }
        // 项目把 deleted 配置为全局逻辑删除字段，selectCount 会自动过滤；显式 eq 是防御性自文档
        LocalDateTime now = LocalDateTime.now();
        long total = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .ge(from != null, Task::getCreatedAt, from)
                .eq(Task::getDeleted, 0));
        long done = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .ge(from != null, Task::getCreatedAt, from)
                .eq(Task::getDeleted, 0)
                .eq(Task::getStatus, TaskStatus.DONE));
        long overdue = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .ge(from != null, Task::getCreatedAt, from)
                .eq(Task::getDeleted, 0)
                .ne(Task::getStatus, TaskStatus.DONE)
                .lt(Task::getDeadline, now));
        long doing = total - done - overdue;

        ReportSummaryVO vo = new ReportSummaryVO();
        vo.setTotal(total);
        vo.setDoing(doing);
        vo.setDone(done);
        vo.setOverdue(overdue);
        vo.setCompletionRate(total == 0 ? 0 : (int) (done * 100 / total));
        vo.setOverdueRate(total == 0 ? 0 : (int) (overdue * 100 / total));
        return vo;
    }
}
