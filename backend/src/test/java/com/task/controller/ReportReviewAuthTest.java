package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.Report;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.entity.TaskMember;
import com.task.enums.AssignType;
import com.task.enums.ReportStatus;
import com.task.enums.TaskStatus;
import com.task.mapper.ReportMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 审核接口权限校验：非审核人（非任务创建者、非管理员）调用 approve/reject
 * 必须返回 403「无权审核该汇报」，无论汇报当前状态如何、参数是否合法——
 * 不允许通过"该汇报已审核"或"驳回必须填写审核内容"等文案泄露汇报状态。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class ReportReviewAuthTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired SysUserMapper userMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired ReportMapper reportMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 本用例自建的数据 id（用于用例结束后按依赖顺序清理，不动种子数据） */
    private final List<Long> createdReportIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // 按依赖顺序物理清理：report → task_member → task（task 表配了逻辑删除，用 JDBC 物理删除）
        for (Long id : createdReportIds) jdbcTemplate.update("DELETE FROM report WHERE id = ?", id);
        for (Long id : createdMemberIds) jdbcTemplate.update("DELETE FROM task_member WHERE id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        createdReportIds.clear();
        createdMemberIds.clear();
        createdTaskIds.clear();
    }

    private String login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    /** 自造数据：任务(creator=leader1) + 成员(zhangsan) + 一条汇报；不依赖手工冒烟数据 */
    private Report createReport(ReportStatus status) {
        SysUser leader = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "leader1"));
        SysUser member = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "zhangsan"));
        Task task = new Task();
        task.setName("审核权限测试-" + System.currentTimeMillis());
        task.setCreatorId(leader.getId());
        task.setAssignType(AssignType.INDIVIDUAL);
        task.setAssigneeId(member.getId());
        task.setStatus(TaskStatus.DOING);
        task.setProgress(0);
        taskMapper.insert(task);
        TaskMember tm = new TaskMember();
        tm.setTaskId(task.getId());
        tm.setUserId(member.getId());
        tm.setWeight(100);
        tm.setProgress(0);
        memberMapper.insert(tm);
        Report r = new Report();
        r.setTaskMemberId(tm.getId());
        r.setUserId(member.getId());
        r.setContent("审核权限测试汇报");
        r.setProgress(50);
        r.setStatus(status);
        reportMapper.insert(r);
        createdTaskIds.add(task.getId());
        createdMemberIds.add(tm.getId());
        createdReportIds.add(r.getId());
        return r;
    }

    @Test
    void nonReviewerCannotApprovePendingReport() throws Exception {
        Report r = createReport(ReportStatus.PENDING);
        String token = login("wangwu", "123456");
        mvc.perform(post("/api/reports/" + r.getId() + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权审核该汇报"));
        // 汇报状态与进度未被改动
        assertEquals(ReportStatus.PENDING, reportMapper.selectById(r.getId()).getStatus());
    }

    @Test
    void nonReviewerCannotApproveReviewedReport() throws Exception {
        Report r = createReport(ReportStatus.APPROVED);
        String token = login("wangwu", "123456");
        mvc.perform(post("/api/reports/" + r.getId() + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权审核该汇报"));
    }

    @Test
    void nonReviewerRejectWithoutReasonStill403() throws Exception {
        Report r = createReport(ReportStatus.PENDING);
        String token = login("wangwu", "123456");
        mvc.perform(post("/api/reports/" + r.getId() + "/reject")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权审核该汇报"));
    }
}
