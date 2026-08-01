package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.entity.Comment;
import com.task.entity.Report;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.entity.TaskMember;
import com.task.enums.ReportStatus;
import com.task.enums.Role;
import com.task.mapper.CommentMapper;
import com.task.mapper.ReportMapper;
import com.task.mapper.SysUserMapper;
import com.task.mapper.TaskMapper;
import com.task.mapper.TaskMemberMapper;
import com.task.service.CommentService;
import com.task.vo.CommentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {
    private final CommentMapper commentMapper;
    private final TaskMapper taskMapper;
    private final ReportMapper reportMapper;
    private final TaskMemberMapper memberMapper;
    private final SysUserMapper userMapper;

    /** 汇报可见性：APPROVED 对所有人可见；PENDING/REJECTED 仅本人、审核人（创建者/管理员）可见 */
    private boolean canSeeReport(Report r, SysUser cur) {
        if (r.getStatus() == ReportStatus.APPROVED) return true;
        if (r.getUserId().equals(cur.getId())) return true;
        if (cur.getRole() == Role.ADMIN) return true;
        TaskMember m = memberMapper.selectById(r.getTaskMemberId());
        Task t = taskMapper.selectById(m.getTaskId());
        return t.getCreatorId().equals(cur.getId());
    }

    private boolean canSeeTask(Long taskId) {
        return taskMapper.selectById(taskId) != null;
    }

    @Override
    public List<CommentVO> taskComments(Long taskId) {
        if (!canSeeTask(taskId)) throw new BusinessException("任务不存在");
        return listVO(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getTaskId, taskId).orderByAsc(Comment::getId));
    }

    @Override
    @Transactional
    public Long addTaskComment(Long taskId, String content) {
        if (!canSeeTask(taskId)) throw new BusinessException("任务不存在");
        Comment c = new Comment();
        c.setTaskId(taskId);
        c.setParentId(0L);
        c.setUserId(UserContext.get().getId());
        c.setContent(content);
        commentMapper.insert(c);
        return c.getId();
    }

    @Override
    public List<CommentVO> reportComments(Long reportId) {
        Report r = reportMapper.selectById(reportId);
        if (r == null) throw new BusinessException("汇报不存在");
        if (!canSeeReport(r, UserContext.get())) {
            throw new BusinessException(403, "无权查看该汇报的评论");
        }
        return listVO(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getReportId, reportId).orderByAsc(Comment::getId));
    }

    @Override
    @Transactional
    public Long addReportComment(Long reportId, String content) {
        Report r = reportMapper.selectById(reportId);
        if (r == null) throw new BusinessException("汇报不存在");
        if (!canSeeReport(r, UserContext.get())) {
            throw new BusinessException(403, "无权评论该汇报");
        }
        Comment c = new Comment();
        c.setReportId(reportId);
        c.setParentId(0L);
        c.setUserId(UserContext.get().getId());
        c.setContent(content);
        commentMapper.insert(c);
        return c.getId();
    }

    @Override
    @Transactional
    public Long reply(Long commentId, String content) {
        Comment parent = commentMapper.selectById(commentId);
        if (parent == null) throw new BusinessException("评论不存在");
        if (parent.getParentId() != 0) throw new BusinessException("只能回复顶级评论");
        Comment c = new Comment();
        if (parent.getTaskId() != null) {
            if (!canSeeTask(parent.getTaskId())) throw new BusinessException("任务不存在");
            c.setTaskId(parent.getTaskId());
        } else {
            Report r = reportMapper.selectById(parent.getReportId());
            if (!canSeeReport(r, UserContext.get())) {
                throw new BusinessException(403, "无权回复该评论");
            }
            c.setReportId(parent.getReportId());
        }
        c.setParentId(parent.getId());
        c.setUserId(UserContext.get().getId());
        c.setContent(content);
        commentMapper.insert(c);
        return c.getId();
    }

    private List<CommentVO> listVO(LambdaQueryWrapper<Comment> qw) {
        List<Comment> list = commentMapper.selectList(qw);
        return list.stream().map(c -> {
            CommentVO vo = new CommentVO();
            vo.setId(c.getId());
            vo.setParentId(c.getParentId());
            vo.setUserId(c.getUserId());
            SysUser u = userMapper.selectById(c.getUserId());
            vo.setUserName(u == null ? null : u.getRealName());
            vo.setContent(c.getContent());
            vo.setCreatedAt(c.getCreatedAt());
            return vo;
        }).collect(Collectors.toList());
    }
}
