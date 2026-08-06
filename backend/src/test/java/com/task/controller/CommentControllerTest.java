package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.Comment;
import com.task.entity.Report;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.entity.TaskMember;
import com.task.enums.AssignType;
import com.task.enums.ReportStatus;
import com.task.enums.TaskStatus;
import com.task.mapper.CommentMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 评论功能测试：任务评论、汇报评论（可见性跟随汇报）、一级回复。
 * 所有 fixture 自建并逐个用例清理，不依赖手工冒烟数据；不跳过断言。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class CommentControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired SysUserMapper userMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired ReportMapper reportMapper;
    @Autowired CommentMapper commentMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 本用例自建的数据 id（用例结束后按依赖顺序清理：comment → report → task_member → task） */
    private final List<Long> createdCommentIds = new ArrayList<>();
    private final List<Long> createdReportIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdCommentIds) jdbcTemplate.update("DELETE FROM comment WHERE id = ?", id);
        // API 创建的评论（任务/汇报/回复）不返回并记录 id，必须按 report_id/task_id 兜底删除，
        // 否则每次运行泄漏 comment 行（Task 5 验证发现的清理缺陷）
        for (Long id : createdReportIds) jdbcTemplate.update("DELETE FROM comment WHERE report_id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM comment WHERE task_id = ?", id);
        for (Long id : createdReportIds) jdbcTemplate.update("DELETE FROM report WHERE id = ?", id);
        for (Long id : createdMemberIds) jdbcTemplate.update("DELETE FROM task_member WHERE id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        createdCommentIds.clear();
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

    private SysUser user(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }

    /** 自建：任务(creator=leader1) + 成员(zhangsan) + 指定状态汇报 */
    private Report createReport(ReportStatus status) {
        SysUser leader = user("leader1");
        SysUser member = user("zhangsan");
        Task task = new Task();
        task.setName("评论测试任务-" + System.currentTimeMillis());
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
        r.setContent("评论测试汇报");
        r.setProgress(50);
        r.setStatus(status);
        reportMapper.insert(r);
        createdTaskIds.add(task.getId());
        createdMemberIds.add(tm.getId());
        createdReportIds.add(r.getId());
        return r;
    }

    /** 自建：任务级顶级评论 */
    private Comment createTaskComment(Long taskId, SysUser author) {
        Comment c = new Comment();
        c.setTaskId(taskId);
        c.setParentId(0L);
        c.setUserId(author.getId());
        c.setContent("任务评论-" + System.currentTimeMillis());
        commentMapper.insert(c);
        createdCommentIds.add(c.getId());
        return c;
    }

    /** 自建：汇报级顶级评论 */
    private Comment createReportComment(Long reportId, SysUser author) {
        Comment c = new Comment();
        c.setReportId(reportId);
        c.setParentId(0L);
        c.setUserId(author.getId());
        c.setContent("汇报评论-" + System.currentTimeMillis());
        commentMapper.insert(c);
        createdCommentIds.add(c.getId());
        return c;
    }

    @Test
    void loggedInUserCanCommentTask() throws Exception {
        Report r = createReport(ReportStatus.PENDING);
        Long taskId = memberMapper.selectById(r.getTaskMemberId()).getTaskId();
        String token = login("wangwu", "123456");
        mvc.perform(post("/api/tasks/" + taskId + "/comments").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"任务加油！\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void thirdPartyCannotViewPendingReportComments() throws Exception {
        Report r = createReport(ReportStatus.PENDING);
        String token = login("wangwu", "123456");
        mvc.perform(get("/api/reports/" + r.getId() + "/comments").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void thirdPartyCannotCommentPendingReport() throws Exception {
        Report r = createReport(ReportStatus.PENDING);
        String token = login("wangwu", "123456");
        mvc.perform(post("/api/reports/" + r.getId() + "/comments").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"偷偷评论\"}"))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void thirdPartyCannotViewOrCommentRejectedReport() throws Exception {
        Report r = createReport(ReportStatus.REJECTED);
        String token = login("wangwu", "123456");
        mvc.perform(get("/api/reports/" + r.getId() + "/comments").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(post("/api/reports/" + r.getId() + "/comments").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"评论被驳回的汇报\"}"))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void approvedReportCommentsVisibleAndCommentable() throws Exception {
        Report r = createReport(ReportStatus.APPROVED);
        // 汇报人 zhangsan 先发一条评论，第三方 wangwu 应能看到并回复评论
        createReportComment(r.getId(), user("zhangsan"));
        String token = login("wangwu", "123456");
        mvc.perform(get("/api/reports/" + r.getId() + "/comments").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].content").isNotEmpty());
        mvc.perform(post("/api/reports/" + r.getId() + "/comments").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"通过后的评论\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void topLevelCommentCanBeReplied() throws Exception {
        Report r = createReport(ReportStatus.APPROVED);
        Comment top = createReportComment(r.getId(), user("zhangsan"));
        String token = login("wangwu", "123456");
        mvc.perform(post("/api/comments/" + top.getId() + "/reply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"回复你\"}"))
                .andExpect(jsonPath("$.code").value(0));
        // 列表应含顶级评论 + 1 条回复
        mvc.perform(get("/api/reports/" + r.getId() + "/comments").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].parentId").value(top.getId()));
    }

    @Test
    void replyCannotReplyToReply() throws Exception {
        Report r = createReport(ReportStatus.APPROVED);
        Comment top = createReportComment(r.getId(), user("zhangsan"));
        String token = login("wangwu", "123456");
        // 先产生一条回复（父=top），再对这条回复回复 → 400 只能回复顶级评论
        String body = mvc.perform(post("/api/comments/" + top.getId() + "/reply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"第一层回复\"}"))
                .andReturn().getResponse().getContentAsString();
        Long replyId = Long.valueOf(body.replaceAll(".*\"data\":(\\d+).*", "$1"));
        mvc.perform(post("/api/comments/" + replyId + "/reply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"第二层回复\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("只能回复顶级评论"));
    }
}
