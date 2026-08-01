package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.common.Result;
import com.task.dto.UserRequest;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import com.task.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private void requireAdmin() {
        if (!"ADMIN".equals(UserContext.get().getRole())) {
            throw new BusinessException(403, "无权操作");
        }
    }

    @GetMapping
    public Result<Page<UserVO>> list(@RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "10") long size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String role) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(keyword), SysUser::getRealName, keyword)
                .eq(StringUtils.hasText(role), SysUser::getRole, role)
                .orderByDesc(SysUser::getId);
        Page<SysUser> p = userMapper.selectPage(new Page<>(page, size), qw);
        Map<Long, String> groupNames = groupMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysGroup::getId, SysGroup::getName));
        Page<UserVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(p.getRecords().stream()
                .map(u -> UserVO.from(u, u.getGroupId() == null ? null : groupNames.get(u.getGroupId())))
                .collect(Collectors.toList()));
        return Result.ok(voPage);
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserRequest req) {
        requireAdmin();
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername())) > 0) {
            throw new BusinessException("用户名已存在");
        }
        SysUser u = new SysUser();
        u.setUsername(req.getUsername());
        u.setPassword(encoder.encode(req.getPassword()));
        u.setRealName(req.getRealName());
        u.setRole(req.getRole());
        u.setGroupId(req.getGroupId());
        userMapper.insert(u);
        return Result.ok(u.getId());
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody UserRequest req) {
        requireAdmin();
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new BusinessException("用户不存在");
        u.setRealName(req.getRealName());
        u.setRole(req.getRole());
        u.setGroupId(req.getGroupId());
        userMapper.updateById(u);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        requireAdmin();
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new BusinessException("用户不存在");
        if (id.equals(UserContext.get().getId())) throw new BusinessException("不能删除自己");
        userMapper.deleteById(id);
        return Result.ok();
    }

    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id,
                                      @RequestBody Map<String, String> body) {
        requireAdmin();
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new BusinessException("用户不存在");
        String pwd = body.get("password");
        if (!StringUtils.hasText(pwd)) throw new BusinessException("密码不能为空");
        u.setPassword(encoder.encode(pwd));
        userMapper.updateById(u);
        return Result.ok();
    }
}
