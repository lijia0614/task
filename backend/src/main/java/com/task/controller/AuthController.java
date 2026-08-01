package com.task.controller;

import com.task.auth.UserContext;
import com.task.common.Result;
import com.task.dto.LoginRequest;
import com.task.dto.LoginResponse;
import com.task.service.AuthService;
import com.task.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req));
    }

    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(authService.me(UserContext.get()));
    }
}
