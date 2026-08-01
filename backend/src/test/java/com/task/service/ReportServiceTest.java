package com.task.service;

import com.task.service.impl.ReportServiceImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {
    /** 汇报进度不能低于当前成员进度（单调递增） */
    @Test
    void progressMustNotDecrease() {
        boolean rejected = false;
        try {
            ReportServiceImpl.checkProgressRule(80, 50); // 当前 80，汇报 50
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        assertTrue(rejected);
    }
}
