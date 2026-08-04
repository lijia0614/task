package com.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.Report;
import com.task.entity.Task;
import com.task.entity.TaskMember;
import com.task.enums.AssignType;
import com.task.enums.ReportStatus;
import com.task.enums.TaskStatus;
import com.task.mapper.ReportMapper;
import com.task.mapper.SysUserMapper;
import com.task.mapper.TaskMapper;
import com.task.mapper.TaskMemberMapper;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 审核补丁：任务逻辑删除后，其汇报的评论属于审计保留数据，
 * 普通业务查询（含提交人/审核人/管理员）不得再通过 /api/reports/{id}/comments 访问；
 * 评论行本身保留不删除。
 * fixture 自建并只清理本用例数据，MinIO 全部 @MockBean。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class DeletedTaskReportCommentsTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired SysUserMapper userMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired ReportMapper reportMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean MinioClient minioClient;

    private final List<Long> createdReportIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdReportIds) {
            jdbcTemplate.update("DELETE FROM comment WHERE report_id = ?", id);
            jdbcTemplate.update("DELETE FROM report_history WHERE report_id = ?", id);
            jdbcTemplate.update("DELETE FROM report WHERE id = ?", id);
        }
        for (Long id : createdMemberIds) jdbcTemplate.update("DELETE FROM task_member WHERE id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        createdReportIds.clear();
        createdMemberIds.clear();
        createdTaskIds.clear();
    }

    private String login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    /** 任务 + 成员 + 一条指定状态汇报 + 一条评论（zhangsan 所发），返回 report id */
    private long createReportWithComment(ReportStatus status) {
        long leaderId = userId("leader1");
        long zhangsanId = userId("zhangsan");
        Task task = new Task();
        task.setName("删除任务评论测试-" + System.currentTimeMillis());
        task.setCreatorId(leaderId);
        task.setAssignType(AssignType.INDIVIDUAL);
        task.setAssigneeId(zhangsanId);
        task.setStatus(TaskStatus.DOING);
        task.setProgress(0);
        taskMapper.insert(task);
        createdTaskIds.add(task.getId());
        TaskMember tm = new TaskMember();
        tm.setTaskId(task.getId());
        tm.setUserId(zhangsanId);
        tm.setWeight(100);
        tm.setProgress(0);
        memberMapper.insert(tm);
        createdMemberIds.add(tm.getId());
        Report r = new Report();
        r.setTaskMemberId(tm.getId());
        r.setUserId(zhangsanId);
        r.setContent("删除任务评论汇报");
        r.setProgress(50);
        r.setStatus(status);
        reportMapper.insert(r);
        createdReportIds.add(r.getId());
        jdbcTemplate.update("INSERT INTO comment (report_id, parent_id, user_id, content) VALUES (?,0,?,?)",
                r.getId(), zhangsanId, "任务删除前的评论");
        return r.getId();
    }

    private int commentCount(long reportId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM comment WHERE report_id = ?", Integer.class, reportId);
    }

    /** 任务删除后，APPROVED 汇报的评论对提交人/审核人均不可访问（现状会放行） */
    @Test
    void approvedReportCommentsOnDeletedTaskInaccessible() throws Exception {
        long reportId = createReportWithComment(ReportStatus.APPROVED);
        long taskId = jdbcTemplate.queryForObject(
                "SELECT task_id FROM task_member WHERE id = (SELECT task_member_id FROM report WHERE id = ?)",
                Long.class, reportId);
        jdbcTemplate.update("UPDATE task SET deleted = 1 WHERE id = ?", taskId);

        String ownerToken = login("zhangsan", "123456");
        mvc.perform(get("/api/reports/" + reportId + "/comments").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权查看该汇报的评论"));
        String reviewerToken = login("leader1", "123456");
        mvc.perform(get("/api/reports/" + reportId + "/comments").header("Authorization", "Bearer " + reviewerToken))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权查看该汇报的评论"));
        // 审计保留：评论行不删除
        assertEquals(1, commentCount(reportId));
    }

    /** 任务删除后，提交人自己的 WITHDRAWN/PENDING 汇报评论同样不可访问 */
    @Test
    void ownWithdrawnReportCommentsOnDeletedTaskInaccessible() throws Exception {
        long reportId = createReportWithComment(ReportStatus.WITHDRAWN);
        long taskId = jdbcTemplate.queryForObject(
                "SELECT task_id FROM task_member WHERE id = (SELECT task_member_id FROM report WHERE id = ?)",
                Long.class, reportId);
        jdbcTemplate.update("UPDATE task SET deleted = 1 WHERE id = ?", taskId);
        String ownerToken = login("zhangsan", "123456");

        mvc.perform(get("/api/reports/" + reportId + "/comments").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权查看该汇报的评论"));
        assertEquals(1, commentCount(reportId));
    }

    /** 任务未删除时评论仍正常可访问（既有契约回归锚点） */
    @Test
    void commentsOnActiveTaskReportStillAccessible() throws Exception {
        long reportId = createReportWithComment(ReportStatus.APPROVED);
        String ownerToken = login("zhangsan", "123456");
        mvc.perform(get("/api/reports/" + reportId + "/comments").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].content").value("任务删除前的评论"));
    }
}
