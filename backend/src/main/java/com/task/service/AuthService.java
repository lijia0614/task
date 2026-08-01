package com.task.service;

import com.task.dto.LoginRequest;
import com.task.dto.LoginResponse;
import com.task.entity.SysUser;
import com.task.vo.UserVO;

public interface AuthService {
    LoginResponse login(LoginRequest req);

    UserVO me(SysUser user);
}
