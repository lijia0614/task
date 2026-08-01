package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.common.Result;
import com.task.dto.GroupRequest;
import com.task.dto.MemberRequest;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import com.task.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {
    private final SysGroupMapper groupMapper;
    private final SysUserMapper userMapper;

    /** 组长只能管理自己的组；管理员任意。非组长/管理员仅可读。 */
    private SysGroup requireManageable(SysGroup g) {
        SysUser cur = UserContext.get();
        if ("ADMIN".equals(cur.getRole())) return g;
        if ("LEADER".equals(cur.getRole()) && cur.getId().equals(g.getLeaderId())) return g;
        throw new BusinessException(403, "无权管理该小组");
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        List<SysGroup> groups = groupMapper.selectList(null);
        List<Map<String, Object>> result = groups.stream().map(g -> {
            Map<String, Object> m = new HashMap<>();
            SysUser leader = userMapper.selectById(g.getLeaderId());
            m.put("id", g.getId());
            m.put("name", g.getName());
            m.put("description", g.getDescription());
            m.put("leaderId", g.getLeaderId());
            m.put("leaderName", leader == null ? null : leader.getRealName());
            m.put("memberCount", userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getGroupId, g.getId())));
            return m;
        }).collect(Collectors.toList());
        return Result.ok(result);
    }

    @GetMapping("/{id}/members")
    public Result<List<UserVO>> members(@PathVariable Long id) {
        SysGroup g = groupMapper.selectById(id);
        if (g == null) throw new BusinessException("小组不存在");
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getGroupId, id));
        return Result.ok(users.stream().map(u -> UserVO.from(u, g.getName()))
                .collect(Collectors.toList()));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody GroupRequest req) {
        SysUser cur = UserContext.get();
        if (!"ADMIN".equals(cur.getRole()) && !"LEADER".equals(cur.getRole())) {
            throw new BusinessException(403, "无权操作");
        }
        if (groupMapper.selectCount(new LambdaQueryWrapper<SysGroup>()
                .eq(SysGroup::getName, req.getName())) > 0) {
            throw new BusinessException("组名已存在");
        }
        SysGroup g = new SysGroup();
        g.setName(req.getName());
        g.setLeaderId(req.getLeaderId());
        g.setDescription(req.getDescription());
        groupMapper.insert(g);
        return Result.ok(g.getId());
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody GroupRequest req) {
        SysGroup g = groupMapper.selectById(id);
        if (g == null) throw new BusinessException("小组不存在");
        requireManageable(g);
        if (groupMapper.selectCount(new LambdaQueryWrapper<SysGroup>()
                .eq(SysGroup::getName, req.getName())
                .ne(SysGroup::getId, id)) > 0) {
            throw new BusinessException("组名已存在");
        }
        g.setName(req.getName());
        g.setLeaderId(req.getLeaderId());
        g.setDescription(req.getDescription());
        groupMapper.updateById(g);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        SysGroup g = groupMapper.selectById(id);
        if (g == null) throw new BusinessException("小组不存在");
        requireManageable(g);
        groupMapper.deleteById(id);
        return Result.ok();
    }

    @PostMapping("/{id}/members")
    public Result<Void> addMember(@PathVariable Long id, @Valid @RequestBody MemberRequest req) {
        SysGroup g = groupMapper.selectById(id);
        if (g == null) throw new BusinessException("小组不存在");
        requireManageable(g);
        SysUser u = userMapper.selectById(req.getUserId());
        if (u == null) throw new BusinessException("用户不存在");
        u.setGroupId(id);
        userMapper.updateById(u);
        return Result.ok();
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        SysGroup g = groupMapper.selectById(id);
        if (g == null) throw new BusinessException("小组不存在");
        requireManageable(g);
        SysUser u = userMapper.selectById(userId);
        if (u == null || !id.equals(u.getGroupId())) throw new BusinessException("该用户不在此组");
        u.setGroupId(null);
        userMapper.updateById(u);
        return Result.ok();
    }
}
