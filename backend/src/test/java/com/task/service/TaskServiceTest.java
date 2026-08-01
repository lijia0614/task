package com.task.service;

import com.task.service.impl.TaskServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskServiceTest {
    /** 权重均分：3 个成员 → 34,33,33（余数给第一个） */
    @Test
    void splitWeightEqually() {
        List<Integer> w = TaskServiceImpl.splitWeights(3);
        assertEquals(Arrays.asList(34, 33, 33), w);
    }

    /** 加权平均：40%/30%/30% 权重，进度 80/50/100 → 77 */
    @Test
    void weightedAverage() {
        List<Integer> weights = List.of(40, 30, 30);
        List<Integer> progresses = List.of(80, 50, 100);
        assertEquals(77, TaskServiceImpl.calcOverallProgress(weights, progresses));
    }
}
