package com.task.controller;

import com.task.auth.JwtUtil;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.common.Result;
import com.task.dto.LoginRequest;
import com.task.dto.LoginResponse;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import com.task.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final SysUserMapper userMapper;
    private final SysGroupMapper groupMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        SysUser user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, req.getUsername()));
        if (user == null || !encoder.matches(req.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }
        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getRole());
        String groupName = user.getGroupId() == null ? null
                : groupMapper.selectById(user.getGroupId()).getName();
        return Result.ok(new LoginResponse(token, UserVO.from(user, groupName)));
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        SysUser u = UserContext.get();
        String groupName = u.getGroupId() == null ? null
                : groupMapper.selectById(u.getGroupId()).getName();
        return Result.ok(UserVO.from(u, groupName));
    }
}
