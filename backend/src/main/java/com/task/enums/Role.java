package com.task.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 用户角色 */
public enum Role {
    EMPLOYEE("EMPLOYEE"), LEADER("LEADER"), ADMIN("ADMIN");

    @EnumValue
    private final String value;

    Role(String value) { this.value = value; }

    public String getValue() { return value; }

    public static Role from(String v) {
        for (Role r : values()) if (r.value.equals(v)) return r;
        throw new IllegalArgumentException("未知角色: " + v);
    }
}
