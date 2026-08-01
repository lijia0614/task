package com.task.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 任务状态 */
public enum TaskStatus {
    DOING("DOING"), DONE("DONE");

    @EnumValue
    private final String value;

    TaskStatus(String value) { this.value = value; }

    public String getValue() { return value; }

    public static TaskStatus from(String v) {
        for (TaskStatus s : values()) if (s.value.equals(v)) return s;
        throw new IllegalArgumentException("未知任务状态: " + v);
    }
}
