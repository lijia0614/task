package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.enums.AssignType;
import com.task.enums.TaskStatus;
import com.task.mapper.SysUserMapper;
import com.task.mapper.TaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理员报表契约（Task 22）：四分类互斥/时间范围/deleted 排除/权限/空态/非法参数 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class AdminReportControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired TaskMapper taskMapper;
    @Autowired SysUserMapper userMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 自建任务 id（物理清理；直插无 task_member 行） */
    private final List<Long> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        createdTaskIds.clear();
    }

    private String login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    /** 直插任务（不设 created_at 时走 DB 默认=当前时间；可指定 status/deadline/createdAt；登记清理 id） */
    private long insertTask(TaskStatus status, LocalDateTime deadline, LocalDateTime createdAt) {
        SysUser leader1 = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "leader1"));
        SysUser zhangsan = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "zhangsan"));
        Task t = new Task();
        t.setName("报表测试任务" + System.currentTimeMillis() + "-" + createdTaskIds.size());
        t.setCreatorId(leader1.getId());
        t.setAssignType(AssignType.INDIVIDUAL);
        t.setAssigneeId(zhangsan.getId());
        t.setStatus(status);
        t.setDeadline(deadline);
        t.setProgress(0);
        if (createdAt != null) t.setCreatedAt(createdAt);
        taskMapper.insert(t);
        createdTaskIds.add(t.getId());
        return t.getId();
    }

    /** 四分类互斥 + deleted 排除：5 条有效（doing3/done1/overdue1）+ 1 条已删除 */
    @Test
    void summaryClassifiesFourExclusiveGroups() throws Exception {
        String token = login("admin", "admin123");
        insertTask(TaskStatus.DOING, LocalDateTime.now().plusDays(5), null);   // 进行中
        insertTask(TaskStatus.DOING, LocalDateTime.now().plusDays(5), null);   // 进行中
        insertTask(TaskStatus.DOING, LocalDateTime.now().minusDays(5), null);  // 已过期
        insertTask(TaskStatus.DONE, LocalDateTime.now().minusDays(5), null);   // 已完成
        insertTask(TaskStatus.DOING, null, null);                              // 无 deadline → 进行中
        long deletedId = insertTask(TaskStatus.DOING, LocalDateTime.now().minusDays(5), null);
        // 项目把 deleted 配置为全局逻辑删除字段，updateById 写不进该列，必须走原生 SQL
        jdbcTemplate.update("UPDATE task SET deleted = 1 WHERE id = ?", deletedId);
        // 自证前提：确认 deleted 真的落库
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT deleted FROM task WHERE id = ?", Integer.class, deletedId));

        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.doing").value(3))
                .andExpect(jsonPath("$.data.done").value(1))
                .andExpect(jsonPath("$.data.overdue").value(1))
                .andExpect(jsonPath("$.data.completionRate").value(20))
                .andExpect(jsonPath("$.data.overdueRate").value(20));
    }

    /** 时间范围按 created_at 过滤（cohort 口径） */
    @Test
    void rangeFiltersByCreatedAt() throws Exception {
        String token = login("admin", "admin123");
        insertTask(TaskStatus.DOING, null, LocalDateTime.now().minusDays(10));
        insertTask(TaskStatus.DOING, null, LocalDateTime.now().minusDays(3));

        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token).param("range", "7d"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));   // 只有 3 天前那条
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token).param("range", "30d"))
                .andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(2));   // 默认 all
    }

    /** 仅管理员：EMPLOYEE/LEADER 一律 403 */
    @Test
    void employeeAndLeaderForbidden() throws Exception {
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + login("zhangsan", "123456")))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + login("leader1", "123456")))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + login("admin", "admin123")))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 范围内无任务 → 全 0、两率 0 */
    @Test
    void emptyRangeReturnsAllZeros() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token).param("range", "7d"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.doing").value(0))
                .andExpect(jsonPath("$.data.done").value(0))
                .andExpect(jsonPath("$.data.overdue").value(0))
                .andExpect(jsonPath("$.data.completionRate").value(0))
                .andExpect(jsonPath("$.data.overdueRate").value(0));
    }

    /** range 非法值 → 400 */
    @Test
    void invalidRangeReturn400() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token).param("range", "foo"))
                .andExpect(jsonPath("$.code").value(400));
    }
}
