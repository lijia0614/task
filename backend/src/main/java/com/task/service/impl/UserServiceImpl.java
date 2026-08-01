package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.dto.UserRequest;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.enums.Role;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import com.task.service.UserService;
import com.task.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private void requireAdmin() {
        if (UserContext.get().getRole() != Role.ADMIN) {
            throw new BusinessException(403, "无权操作");
        }
    }

    @Override
    public Page<UserVO> list(long page, long size, String keyword, String role) {
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
        return voPage;
    }

    @Override
    public Long create(UserRequest req) {
        requireAdmin();
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername())) > 0) {
            throw new BusinessException("用户名已存在");
        }
        SysUser u = new SysUser();
        u.setUsername(req.getUsername());
        u.setPassword(encoder.encode(req.getPassword()));
        u.setRealName(req.getRealName());
        Role role;
        try {
            role = Role.from(req.getRole());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("角色不合法");
        }
        u.setRole(role);
        u.setGroupId(req.getGroupId());
        userMapper.insert(u);
        return u.getId();
    }

    @Override
    public void update(Long id, UserRequest req) {
        requireAdmin();
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new BusinessException("用户不存在");
        u.setRealName(req.getRealName());
        Role role;
        try {
            role = Role.from(req.getRole());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("角色不合法");
        }
        u.setRole(role);
        u.setGroupId(req.getGroupId());
        userMapper.updateById(u);
    }

    @Override
    public void delete(Long id) {
        requireAdmin();
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new BusinessException("用户不存在");
        if (id.equals(UserContext.get().getId())) throw new BusinessException("不能删除自己");
        userMapper.deleteById(id);
    }

    @Override
    public void resetPassword(Long id, String password) {
        requireAdmin();
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new BusinessException("用户不存在");
        if (!StringUtils.hasText(password)) throw new BusinessException("密码不能为空");
        u.setPassword(encoder.encode(password));
        userMapper.updateById(u);
    }
}
