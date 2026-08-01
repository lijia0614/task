package com.task.service;

import com.task.vo.CommentVO;

import java.util.List;

public interface CommentService {
    /** 任务评论列表（所有登录用户可见任务即可查看） */
    List<CommentVO> taskComments(Long taskId);

    /** 发任务评论 */
    Long addTaskComment(Long taskId, String content);

    /** 汇报评论列表（可见性跟随汇报：本人/审核人见全部，其他人仅 APPROVED） */
    List<CommentVO> reportComments(Long reportId);

    /** 评论汇报（权限同上） */
    Long addReportComment(Long reportId, String content);

    /** 回复顶级评论（任务/汇报评论通用） */
    Long reply(Long commentId, String content);
}
