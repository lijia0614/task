package com.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.entity.TaskMember;
import com.task.enums.AssignType;
import com.task.enums.Role;
import com.task.enums.TaskStatus;
import com.task.mapper.SysUserMapper;
import com.task.mapper.TaskMapper;
import com.task.mapper.TaskMemberMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class UserControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired SysUserMapper userMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 本用例自建数据 id（用例结束后按依赖顺序物理清理，不动种子数据） */
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdMemberIds) jdbcTemplate.update("DELETE FROM task_member WHERE id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        for (Long id : createdUserIds) jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", id);
        createdMemberIds.clear();
        createdTaskIds.clear();
        createdUserIds.clear();
    }

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        return body;
    }

    /** admin 创建一个唯一名员工，返回其 id */
    private long createEmployee(String username) throws Exception {
        String token = login("admin", "admin123");
        String body = mvc.perform(post("/api/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\",\"realName\":\"测试员工\",\"role\":\"" + Role.EMPLOYEE.getValue() + "\",\"groupId\":null}"))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(body.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdUserIds.add(id);
        return id;
    }

    @Test
    void employeeCannotCreateUser() throws Exception {
        String token = login("zhangsan", "123456");
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"123456\",\"realName\":\"X\",\"role\":\"" + Role.EMPLOYEE.getValue() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCanCreateUser() throws Exception {
        String token = login("admin", "admin123");
        // 唯一用户名避免重复执行冲突
        String username = "testuser" + System.currentTimeMillis();
        String body = mvc.perform(post("/api/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\",\"realName\":\"测试\",\"role\":\"" + Role.EMPLOYEE.getValue() + "\",\"groupId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        // 记录 id，@AfterEach 清理，避免数据泄漏
        createdUserIds.add(Long.parseLong(body.replaceAll(".*\"data\":(\\d+).*", "$1")));
    }

    /** 规格：删除（有任务成员记录者拒绝） */
    @Test
    void userWithTaskRecordCannotBeDeleted() throws Exception {
        long userId = createEmployee("deletetest" + System.currentTimeMillis());
        // leader1 建个人任务给该用户，产生 task_member 记录（记录 task/task_member id 供 @AfterEach 清理）
        String leaderToken = login("leader1", "123456");
        String taskBody = mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"删除测试任务-" + System.currentTimeMillis() + "\",\"assignType\":\"" + AssignType.INDIVIDUAL.getValue() + "\",\"assigneeId\":" + userId + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long taskId = Long.parseLong(taskBody.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdTaskIds.add(taskId);
        TaskMember tm = memberMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, taskId)
                .eq(TaskMember::getUserId, userId));
        if (tm != null) createdMemberIds.add(tm.getId());
        // admin 删除该用户 → 拒绝
        String adminToken = login("admin", "admin123");
        mvc.perform(delete("/api/users/" + userId).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 分页参数非法（负 size）应返回 400，而不是 500 */
    @Test
    void invalidPagingParamsReturn400() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token)
                        .param("size", "-5"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token)
                        .param("page", "0"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token)
                        .param("size", "1000"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 请求体不是合法 JSON 应返回 400，而不是 500 */
    @Test
    void malformedJsonBodyReturn400() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not a json"))
                .andExpect(jsonPath("$.code").value(400));
    }
}
