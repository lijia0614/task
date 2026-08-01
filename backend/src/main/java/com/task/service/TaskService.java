package com.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.dto.CreateTaskRequest;
import com.task.entity.*;
import com.task.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TaskService {
    private final TaskMapper taskMapper;
    private final TaskMemberMapper memberMapper;
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;

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

    @Transactional
    public Long create(CreateTaskRequest req) {
        SysUser creator = UserContext.get();
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
        return task.getId();
    }
}
