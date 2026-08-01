package com.task.service;

import com.task.service.impl.ReportServiceImpl;
import com.task.service.impl.TaskServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewServiceTest {
    /** 默认均分权重下整体进度 = 成员进度平均 */
    @Test
    void overallProgressWithEqualWeights() {
        // 34/33/33 权重，进度 50/0/0 → 17
        assertEquals(17, TaskServiceImpl.calcOverallProgress(Arrays.asList(34, 33, 33), Arrays.asList(50, 0, 0)));
    }

    /** 手动权重 40/30/30，进度 80/50/100 → 77（规范值） */
    @Test
    void overallProgressWithManualWeights() {
        assertEquals(77, TaskServiceImpl.calcOverallProgress(Arrays.asList(40, 30, 30), Arrays.asList(80, 50, 100)));
    }

    /** 全员 100 才判定任务完成 */
    @Test
    void doneOnlyWhenAllMembersFull() {
        // 权重 50/50，进度 100/50 → 整体 75，未完成
        assertEquals(75, TaskServiceImpl.calcOverallProgress(Arrays.asList(50, 50), Arrays.asList(100, 50)));
        assertFalse(ReportServiceImpl.isAllCompleted(Arrays.asList(100, 50)));
        assertTrue(ReportServiceImpl.isAllCompleted(Arrays.asList(100, 100)));
    }
}
