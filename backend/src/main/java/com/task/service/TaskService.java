package com.task.service;

import com.task.dto.CreateTaskRequest;
import com.task.entity.SysUser;
import com.task.vo.TaskVO;

import java.util.List;
import java.util.Map;

public interface TaskService {
    Long create(CreateTaskRequest req);

    List<TaskVO> list(String type, String status, String keyword, SysUser cur);

    TaskVO detail(Long id);

    void update(Long id, CreateTaskRequest req, SysUser cur);

    void delete(Long id, SysUser cur);

    void updateWeights(Long id, List<Map<String, Integer>> weights, SysUser cur);
}
