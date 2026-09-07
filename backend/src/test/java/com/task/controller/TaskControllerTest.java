package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.entity.TaskMember;
import com.task.enums.AssignType;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 任务列表分页契约（Task 19）：非法参数 400、分页正确性、筛选叠加、空页 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class TaskControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired SysUserMapper userMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 本用例自建数据 id（按依赖顺序物理清理：task_member → task，不动种子数据） */
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdMemberIds) jdbcTemplate.update("DELETE FROM task_member WHERE id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        createdMemberIds.clear();
        createdTaskIds.clear();
    }

    private String login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    /** leader1 建个人任务给种子用户 zhangsan；登记 task 及其全部 task_member id 供清理；返回 taskId */
    private long createTask(String token, String name) throws Exception {
        SysUser zhangsan = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "zhangsan"));
        String body = mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"assignType\":\"" + AssignType.INDIVIDUAL.getValue()
                                + "\",\"assigneeId\":" + zhangsan.getId() + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long taskId = Long.parseLong(body.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdTaskIds.add(taskId);
        for (TaskMember tm : memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, taskId))) {
            createdMemberIds.add(tm.getId());
        }
        return taskId;
    }

    /** 分页参数非法（page<1、size<1、size>1000）必须业务 400；契约与 /api/users 一致 */
    @Test
    void invalidPagingParamsReturn400() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token).param("page", "0"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token).param("size", "-5"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token).param("size", "1001"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 25 条任务：page=2&size=10 恰好 10 条、total=25、两页不重不漏（按 id desc 验证边界） */
    @Test
    void paginationReturnsExactPageAndTotal() throws Exception {
        String token = login("leader1", "123456");
        String p = "分页验收" + System.currentTimeMillis();
        for (int i = 1; i <= 25; i++) createTask(token, p + i);

        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(10))
                // id desc：25..16 在第 1 页，第 2 页首条 = p15
                .andExpect(jsonPath("$.data.records[0].name").value(p + "15"));

        // 第 1 页首条 = p25，且两页 20 条记录互不重复
        String page1 = mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("page", "1").param("size", "10"))
                .andExpect(jsonPath("$.data.records[0].name").value(p + "25"))
                .andReturn().getResponse().getContentAsString();
        String page2 = mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("page", "2").param("size", "10"))
                .andReturn().getResponse().getContentAsString();
        Set<String> names = new HashSet<>();
        for (String body : List.of(page1, page2)) {
            JsonNode tree = om.readTree(body);
            for (JsonNode n : tree.path("data").path("records")) names.add(n.path("name").asText());
        }
        assertEquals(20, names.size(), "两页记录必须不重不漏");
    }

    /** 关键字 + 状态筛选与分页叠加：total 只含过滤后数量 */
    @Test
    void filterCombinesWithPagination() throws Exception {
        String token = login("leader1", "123456");
        String p = "分页验收" + System.currentTimeMillis();
        for (int i = 1; i <= 25; i++) createTask(token, p + i);
        // keyword=p+1 匹配 p1、p10..p19 共 11 条；page=2&size=5 首条 = p14（id desc：19..15 第 1 页）
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p + "1").param("status", "DOING").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.records.length()").value(5))
                .andExpect(jsonPath("$.data.records[0].name").value(p + "14"));
    }

    /** 页码超出范围：返回空 records、total 正常，不报错 */
    @Test
    void emptyPageReturnsEmptyRecords() throws Exception {
        String token = login("leader1", "123456");
        String p = "分页验收" + System.currentTimeMillis();
        for (int i = 1; i <= 25; i++) createTask(token, p + i);
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("page", "99").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }
}
