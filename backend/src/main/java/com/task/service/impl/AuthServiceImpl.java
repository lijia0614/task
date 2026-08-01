package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.JwtUtil;
import com.task.common.BusinessException;
import com.task.dto.LoginRequest;
import com.task.dto.LoginResponse;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import com.task.service.AuthService;
import com.task.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public LoginResponse login(LoginRequest req) {
        SysUser user = userMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, req.getUsername()));
        if (user == null || !encoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getRole());
        return new LoginResponse(token, UserVO.from(user, groupName(user)));
    }

    @Override
    public UserVO me(SysUser user) {
        return UserVO.from(user, groupName(user));
    }

    /** 空安全的小组名解析：用户无组或组记录缺失时返回 null。 */
    private String groupName(SysUser u) {
        if (u.getGroupId() == null) return null;
        SysGroup g = groupMapper.selectById(u.getGroupId());
        return g == null ? null : g.getName();
    }
}
