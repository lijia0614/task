package com.task.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 汇报状态 */
public enum ReportStatus {
    PENDING("PENDING"), APPROVED("APPROVED"), REJECTED("REJECTED"), WITHDRAWN("WITHDRAWN");

    @EnumValue
    private final String value;

    ReportStatus(String value) { this.value = value; }

    public String getValue() { return value; }

    public static ReportStatus from(String v) {
        for (ReportStatus s : values()) if (s.value.equals(v)) return s;
        throw new IllegalArgumentException("未知汇报状态: " + v);
    }
}
