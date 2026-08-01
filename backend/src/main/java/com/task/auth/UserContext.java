package com.task.auth;

import com.task.entity.SysUser;

public class UserContext {
    private static final ThreadLocal<SysUser> HOLDER = new ThreadLocal<>();

    public static void set(SysUser user) { HOLDER.set(user); }

    public static SysUser get() {
        SysUser u = HOLDER.get();
        if (u == null) throw new com.task.common.BusinessException(401, "未登录");
        return u;
    }

    public static void clear() { HOLDER.remove(); }
}
