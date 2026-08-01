package com.task.auth;

import com.task.common.BusinessException;
import com.task.entity.SysUser;
import com.task.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new BusinessException(401, "未登录");
        }
        try {
            String token = auth.substring(7);
            Long userId = jwtUtil.parseUserId(token);
            SysUser user = userMapper.selectById(userId);
            if (user == null) throw new BusinessException(401, "用户不存在");
            UserContext.set(user);
            return true;
        } catch (Exception e) {
            if (e instanceof BusinessException be) throw be;
            throw new BusinessException(401, "登录已过期");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
