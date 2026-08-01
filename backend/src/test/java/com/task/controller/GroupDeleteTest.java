package com.task.controller;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 规格：删除小组（组内有未完成任务者拒绝）。
 * fixture 自建并清理，不依赖手工冒烟数据。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class GroupDeleteTest {
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

    /** 自建：新组（组长 leader1）+ 一个新员工入组，返回组 id */
    private long createGroupWithMember() throws Exception {
        String adminToken = login("admin", "admin123");
        String groupName = "删除测试组-" + System.currentTimeMillis();
        SysUser leader = userMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "leader1"));
        // 建组
        String groupBody = mvc.perform(post("/api/groups").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + groupName + "\",\"leaderId\":" + leader.getId() + ",\"description\":\"测试\"}"))
                .andReturn().getResponse().getContentAsString();
        long groupId = Long.parseLong(groupBody.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdGroupIds.add(groupId);
        // 建一个员工并入组
        String username = "grouptest" + System.currentTimeMillis();
        String userBody = mvc.perform(post("/api/users").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"123456\",\"realName\":\"组测试员\",\"role\":\"" + Role.EMPLOYEE.getValue() + "\",\"groupId\":null}"))
                .andReturn().getResponse().getContentAsString();
        long userId = Long.parseLong(userBody.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdUserIds.add(userId);
        mvc.perform(post("/api/groups/" + groupId + "/members").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + userId + "}"))
                .andExpect(jsonPath("$.code").value(0));
        return groupId;
    }

    /** 组内有未完成（DOING）任务时删除被拒绝 */
    @Test
    void groupWithUnfinishedTaskCannotBeDeleted() throws Exception {
        long groupId = createGroupWithMember();
        String leaderToken = login("leader1", "123456");
        // 建组任务（未完成），记录 task/task_member id 供 @AfterEach 清理
        String taskBody = mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"组删除测试任务-" + System.currentTimeMillis() + "\",\"assignType\":\"" + AssignType.GROUP.getValue() + "\",\"assigneeId\":" + groupId + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long taskId = Long.parseLong(taskBody.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdTaskIds.add(taskId);
        // 组任务的 task_member 记录（组内成员的）也记录，清理时先删
        for (TaskMember tm : memberMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskMember>()
                        .eq(TaskMember::getTaskId, taskId))) {
            createdMemberIds.add(tm.getId());
        }
        String adminToken = login("admin", "admin123");
        mvc.perform(delete("/api/groups/" + groupId).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(400));
    }
}
