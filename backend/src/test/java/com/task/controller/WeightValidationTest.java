package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.entity.TaskMember;
import com.task.enums.AssignType;
import com.task.enums.Role;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 契约：创建任务时手动 weights 的校验规则——
 * GROUP：每项 >0 且 <=100、总和 =100；INDIVIDUAL：忽略 weights。
 * fixture 自建并清理，不依赖手工冒烟数据。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class WeightValidationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired SysGroupMapper groupMapper;
    @Autowired SysUserMapper userMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 本用例自建数据 id（按依赖顺序物理清理：task_member → task → sys_group → sys_user） */
    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdGroupIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdMemberIds) jdbcTemplate.update("DELETE FROM task_member WHERE id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        for (Long id : createdGroupIds) jdbcTemplate.update("DELETE FROM sys_group WHERE id = ?", id);
        for (Long id : createdUserIds) jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", id);
        createdMemberIds.clear();
        createdTaskIds.clear();
        createdGroupIds.clear();
        createdUserIds.clear();
    }

    private String login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    /** 自建：新组（组长 leader1）+ n 名新员工入组，返回组 id */
    private long createGroup(int memberCount) throws Exception {
        String adminToken = login("admin", "admin123");
        SysUser leader = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "leader1"));
        String groupBody = mvc.perform(post("/api/groups").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"权重测试组-" + System.currentTimeMillis() + "\",\"leaderId\":" + leader.getId() + ",\"description\":\"测试\"}"))
                .andReturn().getResponse().getContentAsString();
        long groupId = Long.parseLong(groupBody.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdGroupIds.add(groupId);
        for (int i = 0; i < memberCount; i++) {
            String username = "weighttest" + System.currentTimeMillis() + "_" + i;
            String userBody = mvc.perform(post("/api/users").header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"123456\",\"realName\":\"权重测试员\",\"role\":\"" + Role.EMPLOYEE.getValue() + "\",\"groupId\":null}"))
                    .andReturn().getResponse().getContentAsString();
            long userId = Long.parseLong(userBody.replaceAll(".*\"data\":(\\d+).*", "$1"));
            createdUserIds.add(userId);
            mvc.perform(post("/api/groups/" + groupId + "/members").header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + userId + "}"))
                    .andExpect(jsonPath("$.code").value(0));
        }
        return groupId;
    }

    /**
     * 创建任务（单次请求）：断言 code=0，解析 taskId 并记录该 task 及其全部 task_member id，
     * 供 @AfterEach 按依赖顺序清理（task_member → task）。成功创建只发一次请求。
     */
    private void createTaskAndTrack(String token, String assignType, Long assigneeId, String weightsJson) throws Exception {
        String body = mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"权重测试任务-" + System.currentTimeMillis() + "\",\"assignType\":\"" + assignType
                                + "\",\"assigneeId\":" + assigneeId
                                + (weightsJson == null ? "" : ",\"weights\":" + weightsJson) + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long taskId = Long.parseLong(body.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdTaskIds.add(taskId);
        // 记录该 task 的全部 task_member（组任务/个人任务都有）
        for (TaskMember tm : memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, taskId))) {
            createdMemberIds.add(tm.getId());
        }
    }

    private long groupMemberId(long groupId) {
        // 组内第一个成员的用户 id（用于个人任务 assignee）
        List<SysUser> members = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getGroupId, groupId));
        return members.get(0).getId();
    }

    /** 组任务权重每项必须 >0 */
    @Test
    void groupTaskWeightZeroRejected() throws Exception {
        long groupId = createGroup(1);
        String leaderToken = login("leader1", "123456");
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"权重测试任务-x\",\"assignType\":\"GROUP\",\"assigneeId\":" + groupId + ",\"weights\":[0]}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 组任务权重每项必须 <=100 */
    @Test
    void groupTaskWeightOver100Rejected() throws Exception {
        long groupId = createGroup(1);
        String leaderToken = login("leader1", "123456");
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"权重测试任务-x\",\"assignType\":\"GROUP\",\"assigneeId\":" + groupId + ",\"weights\":[101]}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 组任务权重总和必须 =100 */
    @Test
    void groupTaskWeightsNotSum100Rejected() throws Exception {
        long groupId = createGroup(2);
        String leaderToken = login("leader1", "123456");
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"权重测试任务-x\",\"assignType\":\"GROUP\",\"assigneeId\":" + groupId + ",\"weights\":[30,30]}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 组任务合法权重（每项 1..100、总和 100）通过；记录 task 及全部 task_member 供清理 */
    @Test
    void groupTaskValidWeightsAccepted() throws Exception {
        long groupId = createGroup(2);
        String leaderToken = login("leader1", "123456");
        createTaskAndTrack(leaderToken, AssignType.GROUP.getValue(), groupId, "[60,40]");
    }

    /** 个人任务忽略 weights（即使非法也按 weight=100 创建成功）；记录 task 及 task_member 供清理 */
    @Test
    void individualTaskIgnoresWeights() throws Exception {
        long groupId = createGroup(1);
        long userId = groupMemberId(groupId);
        String leaderToken = login("leader1", "123456");
        createTaskAndTrack(leaderToken, AssignType.INDIVIDUAL.getValue(), userId, "[0]");
    }
}
