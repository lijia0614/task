package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.dto.CreateTaskRequest;
import com.task.entity.*;
import com.task.enums.AssignType;
import com.task.enums.ReportStatus;
import com.task.enums.Role;
import com.task.enums.TaskStatus;
import com.task.mapper.*;
import com.task.service.MinioObjectService;
import com.task.service.TaskService;
import com.task.vo.AttachmentVO;
import com.task.vo.TaskMemberVO;
import com.task.vo.TaskVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final MinioObjectService minioObjectService;

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
        return cur.getRole() == Role.ADMIN || t.getCreatorId().equals(cur.getId());
    }

    @Override
    @Transactional
    public Long create(CreateTaskRequest req) {
        SysUser creator = UserContext.get();
        if (creator.getRole() != Role.ADMIN && creator.getRole() != Role.LEADER) {
            throw new BusinessException(403, "无权创建任务");
        }
        Task task = new Task();
        task.setName(req.getName());
        task.setDescription(req.getDescription());
        task.setCreatorId(creator.getId());
        task.setAssigneeId(req.getAssigneeId());
        task.setDeadline(req.getDeadline());
        task.setStatus(TaskStatus.DOING);
        task.setProgress(0);

        List<SysUser> assignees;
        AssignType assignType;
        try {
            assignType = AssignType.from(req.getAssignType());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("分配类型不合法");
        }
        if (assignType == AssignType.INDIVIDUAL) {
            SysUser u = userMapper.selectById(req.getAssigneeId());
            if (u == null) throw new BusinessException("用户不存在");
            task.setAssignType(AssignType.INDIVIDUAL);
            assignees = List.of(u);
        } else if (assignType == AssignType.GROUP) {
            SysGroup g = groupMapper.selectById(req.getAssigneeId());
            if (g == null) throw new BusinessException("小组不存在");
            task.setAssignType(AssignType.GROUP);
            // 权重数组按组员 id 升序映射，查询必须显式排序保证稳定
            assignees = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getGroupId, g.getId())
                    .orderByAsc(SysUser::getId));
            if (assignees.isEmpty()) throw new BusinessException("小组没有成员");
        } else {
            throw new BusinessException("分配类型不合法");
        }
        taskMapper.insert(task);

        // 权重规则：weights 为 null 时默认均分；GROUP 显式传 weights 必须数量与成员数一致、
        // 每项 >0 且 <=100、总和 =100（不允许静默回退）；个人任务忽略 weights（固定 weight=100）
        List<Integer> weights;
        if (req.getWeights() == null) {
            weights = splitWeights(assignees.size());
        } else if (assignType == AssignType.GROUP) {
            if (req.getWeights().size() != assignees.size()) {
                throw new BusinessException("权重数量与成员数不一致");
            }
            int sum = 0;
            for (Integer w : req.getWeights()) {
                if (w == null || w <= 0 || w > 100) throw new BusinessException("权重必须为 1-100 的整数");
                sum += w;
            }
            if (sum != 100) throw new BusinessException("权重总和必须为 100");
            weights = req.getWeights();
        } else {
            weights = splitWeights(assignees.size());
        }
        for (int i = 0; i < assignees.size(); i++) {
            TaskMember m = new TaskMember();
            m.setTaskId(task.getId());
            m.setUserId(assignees.get(i).getId());
            m.setWeight(weights.get(i));
            m.setProgress(0);
            memberMapper.insert(m);
        }
        // 关联附件：先整体校验（重复/存在/归属/未绑定），任一非法则整个任务创建失败
        if (req.getAttachmentIds() != null && !req.getAttachmentIds().isEmpty()) {
            List<Long> ids = req.getAttachmentIds();
            if (ids.stream().anyMatch(Objects::isNull)) {
                throw new BusinessException("附件 ID 不能为空");
            }
            if (ids.stream().distinct().count() != ids.size()) {
                throw new BusinessException("附件列表不能重复");
            }
            // 按 id 升序锁行，统一加锁顺序降低死锁风险
            List<MinioFile> files = minioFileMapper.selectByIdsForUpdate(
                    ids.stream().sorted().collect(Collectors.toList()));
            if (files.size() != ids.size()) throw new BusinessException("附件不存在");
            for (MinioFile f : files) {
                if (!creator.getId().equals(f.getUploaderId())) {
                    throw new BusinessException("只能使用自己上传的文件");
                }
                if (attachmentMapper.selectByMinioFileIdForUpdate(f.getId()) != null) {
                    throw new BusinessException("附件已被其他任务绑定");
                }
            }
            for (MinioFile f : files) {
                TaskAttachment a = new TaskAttachment();
                a.setTaskId(task.getId());
                a.setMinioFileId(f.getId());
                a.setFileName(f.getFileName());
                a.setFileUrl(minioPublicUrl + "/" + minioBucket + "/" + f.getObjectName());
                a.setFileSize(f.getSize());
                a.setUploadedBy(f.getUploaderId());
                attachmentMapper.insert(a);
            }
        }
        return task.getId();
    }

    @Override
    public Page<TaskVO> list(long page, long size, String type, String status, String keyword, SysUser cur) {
        if (page < 1 || size < 1 || size > 1000) throw new BusinessException("分页参数不合法");
        List<Long> myTaskIds = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                        .eq(TaskMember::getUserId, cur.getId()))
                .stream().map(TaskMember::getTaskId).collect(Collectors.toList());

        boolean overdue = "OVERDUE".equals(status);
        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<Task>()
                .eq(!overdue && StringUtils.hasText(status), Task::getStatus, status)
                // 已过期：未完成且过了 deadline（Java 侧本地时间，与前端标红口径一致；不用 SQL NOW() 避免容器 UTC 偏差）
                .lt(overdue, Task::getDeadline, LocalDateTime.now())
                .ne(overdue, Task::getStatus, TaskStatus.DONE)
                .like(StringUtils.hasText(keyword), Task::getName, keyword);
        if (overdue) {
            qw.orderByAsc(Task::getDeadline).orderByDesc(Task::getId);
        } else {
            qw.orderByDesc(Task::getId);
        }
        if ("mine_created".equals(type)) {
            qw.eq(Task::getCreatorId, cur.getId());
        } else if ("assigned".equals(type)) {
            if (myTaskIds.isEmpty()) return new Page<>(page, size);
            qw.in(Task::getId, myTaskIds);
        }
        Page<Task> p = taskMapper.selectPage(new Page<>(page, size), qw);
        Page<TaskVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(p.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return voPage;
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

    /**
     * 只允许创建者本人删除（不用 canManage，管理员无权删他人任务）。
     * 锁序 task → members，与提交汇报一致：锁 task 行校验未删除，再锁成员行检查
     * 待审核汇报，杜绝"删除检查后插入新 PENDING"的并发竞态。
     * 附件先整体校验（minio_file_id 非空、文件存在、上传者匹配），再删 MinIO、
     * 删 attachment/minio 行，最后逻辑删除任务；MinIO 失败则整件事务回滚。
     */
    @Override
    @Transactional
    public void delete(Long id, SysUser cur) {
        Task t = taskMapper.selectByIdForUpdate(id);
        if (t == null || (t.getDeleted() != null && t.getDeleted() == 1)) {
            throw new BusinessException("任务不存在");
        }
        if (!t.getCreatorId().equals(cur.getId())) throw new BusinessException(403, "无权删除任务");
        List<Long> memberIds = memberMapper.selectByTaskIdForUpdate(id)
                .stream().map(TaskMember::getId).collect(Collectors.toList());
        if (!memberIds.isEmpty()) {
            Long pending = reportMapper.selectCount(new LambdaQueryWrapper<Report>()
                    .in(Report::getTaskMemberId, memberIds)
                    .eq(Report::getStatus, ReportStatus.PENDING));
            if (pending > 0) throw new BusinessException("存在待审核汇报，无法删除");
        }
        List<TaskAttachment> attachments = attachmentMapper.selectList(
                new LambdaQueryWrapper<TaskAttachment>().eq(TaskAttachment::getTaskId, id));
        if (!attachments.isEmpty()) {
            List<Long> fileIds = attachments.stream().map(TaskAttachment::getMinioFileId)
                    .sorted(Comparator.nullsFirst(Long::compareTo))
                    .collect(Collectors.toList());
            if (fileIds.stream().anyMatch(Objects::isNull)) {
                throw new BusinessException("存在未关联的遗留附件，无法删除");
            }
            List<MinioFile> files = minioFileMapper.selectByIdsForUpdate(fileIds);
            Map<Long, MinioFile> byId = files.stream().collect(Collectors.toMap(MinioFile::getId, x -> x));
            for (TaskAttachment a : attachments) {
                MinioFile f = byId.get(a.getMinioFileId());
                if (f == null) throw new BusinessException("附件关联的文件不存在，无法删除");
                if (!cur.getId().equals(f.getUploaderId())
                        || !cur.getId().equals(a.getUploadedBy())
                        || !Objects.equals(a.getUploadedBy(), f.getUploaderId())) {
                    throw new BusinessException("附件归属异常，无法删除");
                }
            }
            for (MinioFile f : files) minioObjectService.delete(f.getObjectName());
            for (TaskAttachment a : attachments) attachmentMapper.deleteById(a.getId());
            for (MinioFile f : files) minioFileMapper.deleteById(f.getId());
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
        vo.setAssignType(t.getAssignType() == null ? null : t.getAssignType().name());
        vo.setAssigneeId(t.getAssigneeId());
        if (t.getAssignType() == AssignType.INDIVIDUAL) {
            SysUser u = userMapper.selectById(t.getAssigneeId());
            vo.setAssigneeName(u == null ? null : u.getRealName());
        } else {
            SysGroup g = groupMapper.selectById(t.getAssigneeId());
            vo.setAssigneeName(g == null ? null : g.getName());
        }
        vo.setStatus(t.getStatus() == null ? null : t.getStatus().name());
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
