package com.task.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 分配类型 */
public enum AssignType {
    INDIVIDUAL("INDIVIDUAL"), GROUP("GROUP");

    @EnumValue
    private final String value;

    AssignType(String value) { this.value = value; }

    public String getValue() { return value; }

    public static AssignType from(String v) {
        for (AssignType t : values()) if (t.value.equals(v)) return t;
        throw new IllegalArgumentException("未知分配类型: " + v);
    }
}
