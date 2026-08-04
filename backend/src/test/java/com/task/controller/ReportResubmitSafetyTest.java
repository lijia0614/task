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
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 审核补丁：resubmit 必须重新校验所属任务未删除（否则「任务不存在」，状态保持 WITHDRAWN，
 * 不写历史）与当前汇报进度（不低于 task_member.progress 且 ≤100）；
 * 锁顺序 report → task → member，与 delete/submit 的 task → member 一致，无死锁环。
 * fixture 自建并只清理本用例数据，MinIO 全部 @MockBean。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class ReportResubmitSafetyTest {
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

    /** 任务 + 成员 + WITHDRAWN 汇报（可指定汇报进度/成员进度），返回 report id */
    private long createWithdrawn(int reportProgress, int memberProgress) {
        long leaderId = userId("leader1");
        long zhangsanId = userId("zhangsan");
        Task task = new Task();
        task.setName("重提安全测试-" + System.currentTimeMillis());
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
        tm.setProgress(memberProgress);
        memberMapper.insert(tm);
        createdMemberIds.add(tm.getId());
        Report r = new Report();
        r.setTaskMemberId(tm.getId());
        r.setUserId(zhangsanId);
        r.setContent("重提安全汇报");
        r.setProgress(reportProgress);
        r.setStatus(ReportStatus.WITHDRAWN);
        reportMapper.insert(r);
        createdReportIds.add(r.getId());
        return r.getId();
    }

    private int historyCount(long reportId, String action) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report_history WHERE report_id = ? AND action = ?", Integer.class, reportId, action);
    }

    /** 所属任务已逻辑删除：resubmit 返回「任务不存在」，状态保持 WITHDRAWN，不写历史 */
    @Test
    void resubmitOnDeletedTaskRejected() throws Exception {
        long reportId = createWithdrawn(50, 0);
        long taskId = jdbcTemplate.queryForObject(
                "SELECT task_id FROM task_member WHERE id = (SELECT task_member_id FROM report WHERE id = ?)",
                Long.class, reportId);
        jdbcTemplate.update("UPDATE task SET deleted = 1 WHERE id = ?", taskId);
        String token = login("zhangsan", "123456");

        mvc.perform(post("/api/reports/" + reportId + "/resubmit").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("任务不存在"));
        assertEquals(ReportStatus.WITHDRAWN, reportMapper.selectById(reportId).getStatus());
        assertEquals(0, historyCount(reportId, "RESUBMITTED"));
    }

    /** 汇报进度低于成员当前进度：拒绝，保持 WITHDRAWN，不写历史 */
    @Test
    void resubmitBelowMemberProgressRejected() throws Exception {
        long reportId = createWithdrawn(50, 60);
        String token = login("zhangsan", "123456");

        mvc.perform(post("/api/reports/" + reportId + "/resubmit").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("进度不能低于当前进度"));
        assertEquals(ReportStatus.WITHDRAWN, reportMapper.selectById(reportId).getStatus());
        assertEquals(0, historyCount(reportId, "RESUBMITTED"));
    }

    /** 汇报进度超过 100（脏数据兜底）：拒绝，保持 WITHDRAWN，不写历史 */
    @Test
    void resubmitOver100Rejected() throws Exception {
        long reportId = createWithdrawn(120, 0);
        String token = login("zhangsan", "123456");

        mvc.perform(post("/api/reports/" + reportId + "/resubmit").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("进度不能超过 100"));
        assertEquals(ReportStatus.WITHDRAWN, reportMapper.selectById(reportId).getStatus());
        assertEquals(0, historyCount(reportId, "RESUBMITTED"));
    }

    /** 删除与重提并发：最终不得出现已删除任务上的 PENDING 汇报 */
    @Test
    void concurrentDeleteAndResubmitNoPendingOnDeletedTask() throws Exception {
        long reportId = createWithdrawn(50, 0);
        long taskId = jdbcTemplate.queryForObject(
                "SELECT task_id FROM task_member WHERE id = (SELECT task_member_id FROM report WHERE id = ?)",
                Long.class, reportId);
        String leaderToken = login("leader1", "123456");
        String zhangToken = login("zhangsan", "123456");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> codes = java.util.Collections.synchronizedList(new ArrayList<>());
        pool.submit(() -> {
            try {
                start.await();
                MvcResult res = mvc.perform(delete("/api/tasks/" + taskId).header("Authorization", "Bearer " + leaderToken))
                        .andReturn();
                codes.add(om.readTree(res.getResponse().getContentAsString()).get("code").asInt());
            } catch (Exception ignored) { }
        });
        pool.submit(() -> {
            try {
                start.await();
                MvcResult res = mvc.perform(post("/api/reports/" + reportId + "/resubmit")
                                .header("Authorization", "Bearer " + zhangToken))
                        .andReturn();
                codes.add(om.readTree(res.getResponse().getContentAsString()).get("code").asInt());
            } catch (Exception ignored) { }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "并发请求未在超时内结束");
        assertEquals(2, codes.size(), "两个请求都必须完成");

        int deleted = jdbcTemplate.queryForObject("SELECT deleted FROM task WHERE id = ?", Integer.class, taskId);
        ReportStatus status = reportMapper.selectById(reportId).getStatus();
        if (deleted == 1) {
            assertEquals(ReportStatus.WITHDRAWN, status, "已删除任务上绝不能出现 PENDING 汇报");
        } else {
            assertEquals(ReportStatus.PENDING, status, "任务未删除则重提应成功");
        }
    }
}
