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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    void employeeCannotListUsers() throws Exception {
        String token = login("zhangsan", "123456");
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void leaderCannotListUsers() throws Exception {
        String token = login("leader1", "123456");
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void adminCanListUsers() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void leaderCanListUserCandidates() throws Exception {
        String token = login("leader1", "123456");
        mvc.perform(get("/api/users/candidates").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void adminCanListUserCandidates() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/users/candidates").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void employeeCannotListUserCandidates() throws Exception {
        String token = login("zhangsan", "123456");
        mvc.perform(get("/api/users/candidates").header("Authorization", "Bearer " + token))
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

    /** 重置密码成功：新密码可登录（验证 BCrypt 更新生效） */
    @Test
    void adminCanResetPassword() throws Exception {
        String username = "resettest" + System.currentTimeMillis();
        long userId = createEmployee(username);
        String token = login("admin", "admin123");
        mvc.perform(put("/api/users/" + userId + "/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"newpass6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("username", username, "password", "newpass6"))))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 规格/UI 契约：密码至少 6 位；直接 PUT 短密码必须拒绝，且原密码不受影响 */
    @Test
    void resetPasswordShortPasswordRejected() throws Exception {
        String username = "shortpass" + System.currentTimeMillis();
        long userId = createEmployee(username);
        String token = login("admin", "admin123");
        mvc.perform(put("/api/users/" + userId + "/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        // 密码未被篡改：原密码仍可登录
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("username", username, "password", "123456"))))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 编辑用户：请求体不含 username/password（用户名不可改，密码走独立重置接口）；
     *  修复前 UserRequest @NotBlank 使该载荷必然 400，编辑功能不可用 */
    @Test
    void adminCanUpdateUser() throws Exception {
        long userId = createEmployee("updatetest" + System.currentTimeMillis());
        String token = login("admin", "admin123");
        mvc.perform(put("/api/users/" + userId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"更新后的姓名\",\"role\":\"LEADER\",\"groupId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        SysUser updated = userMapper.selectById(userId);
        assertEquals("更新后的姓名", updated.getRealName());
        assertEquals(Role.LEADER, updated.getRole());
    }

    /** 编辑用户缺姓名必须 400（UpdateUserRequest @NotBlank） */
    @Test
    void updateRequiresRealName() throws Exception {
        long userId = createEmployee("updname" + System.currentTimeMillis());
        String token = login("admin", "admin123");
        mvc.perform(put("/api/users/" + userId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EMPLOYEE\",\"groupId\":null}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 编辑用户仅管理员；员工 403 */
    @Test
    void employeeCannotUpdateUser() throws Exception {
        long userId = createEmployee("upd403" + System.currentTimeMillis());
        String token = login("zhangsan", "123456");
        mvc.perform(put("/api/users/" + userId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"x\",\"role\":\"EMPLOYEE\",\"groupId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    /** 分页参数非法应返回 400；契约：size 上限为 1000 */
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
                        .param("size", "1001"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 契约：size=999（接近上限）必须合法 */
    @Test
    void pagingSize999Accepted() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + token)
                        .param("page", "1").param("size", "999"))
                .andExpect(jsonPath("$.code").value(0));
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
