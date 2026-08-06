package com.task.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 规格：同一任务成员同一时间最多一条 PENDING 汇报；审核/撤回/重提并发时
 * 状态已变者返回「汇报状态已变化，请刷新」；已逻辑删除的任务不能再提交汇报；
 * 任务删除与汇报提交通过一致的 task→task_member 锁顺序串行化，杜绝删除后残留 PENDING。
 * fixture 自建并只清理本用例数据，MinIO 全部 @MockBean。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class ReportPendingUniquenessTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired SysUserMapper userMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired ReportMapper reportMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean MinioClient minioClient;

    /** 自建数据 id（按依赖顺序物理清理：report_history → report → task_member → task） */
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

    /** 自造数据：任务(creator=leader1) + 成员(zhangsan, progress=0)，返回 task id */
    private long createTaskWithMember() {
        long leaderId = userId("leader1");
        long zhangsanId = userId("zhangsan");
        Task task = new Task();
        task.setName("待审唯一性测试-" + System.currentTimeMillis());
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
        return task.getId();
    }

    private Report insertReport(long taskId, ReportStatus status) {
        long zhangsanId = userId("zhangsan");
        long memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM task_member WHERE task_id = ?", Long.class, taskId);
        Report r = new Report();
        r.setTaskMemberId(memberId);
        r.setUserId(zhangsanId);
        r.setContent("待审唯一性汇报");
        r.setProgress(50);
        r.setStatus(status);
        reportMapper.insert(r);
        createdReportIds.add(r.getId());
        return r;
    }

    private int pendingCount(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report r JOIN task_member tm ON r.task_member_id = tm.id "
                        + "WHERE tm.task_id = ? AND r.status = 'PENDING'", Integer.class, taskId);
    }

    private int reportCount(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report r JOIN task_member tm ON r.task_member_id = tm.id "
                        + "WHERE tm.task_id = ?", Integer.class, taskId);
    }

    /** 已有 PENDING 时再次提交：400，不产生第二条 */
    @Test
    void secondSubmitWhileOnePendingRejected() throws Exception {
        long taskId = createTaskWithMember();
        insertReport(taskId, ReportStatus.PENDING);
        String token = login("zhangsan", "123456");

        mvc.perform(post("/api/tasks/" + taskId + "/reports").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content", "第二次提交", "progress", 60))))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("存在待审核的汇报，请先等待审核或撤回"));
        assertEquals(1, reportCount(taskId));
    }

    /** 成员已有另一条 PENDING 时，WITHDRAWN 重新提交也被拒绝 */
    @Test
    void resubmitWhileAnotherPendingExistsRejected() throws Exception {
        long taskId = createTaskWithMember();
        insertReport(taskId, ReportStatus.PENDING);
        Report withdrawn = insertReport(taskId, ReportStatus.WITHDRAWN);
        String token = login("zhangsan", "123456");

        mvc.perform(post("/api/reports/" + withdrawn.getId() + "/resubmit").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("存在待审核的汇报，请先等待审核或撤回"));
        assertEquals(ReportStatus.WITHDRAWN, reportMapper.selectById(withdrawn.getId()).getStatus());
    }

    /** 撤回后审核：400 状态已变化 */
    @Test
    void approveAfterWithdrawalRejected() throws Exception {
        long taskId = createTaskWithMember();
        Report r = insertReport(taskId, ReportStatus.WITHDRAWN);
        String leaderToken = login("leader1", "123456");

        mvc.perform(post("/api/reports/" + r.getId() + "/approve").header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("progress", 60, "reviewComment", "通过"))))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("汇报状态已变化，请刷新"));
        assertEquals(ReportStatus.WITHDRAWN, reportMapper.selectById(r.getId()).getStatus());
    }

    /** 审核后撤回：400 状态已变化 */
    @Test
    void withdrawAfterApproveRejected() throws Exception {
        long taskId = createTaskWithMember();
        Report r = insertReport(taskId, ReportStatus.APPROVED);
        String token = login("zhangsan", "123456");

        mvc.perform(post("/api/reports/" + r.getId() + "/withdraw").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("汇报状态已变化，请刷新"));
        assertEquals(ReportStatus.APPROVED, reportMapper.selectById(r.getId()).getStatus());
    }

    /** P1：已逻辑删除的任务不能再提交汇报，不产生任何记录 */
    @Test
    void submitToDeletedTaskRejected() throws Exception {
        long taskId = createTaskWithMember();
        jdbcTemplate.update("UPDATE task SET deleted = 1 WHERE id = ?", taskId);
        String token = login("zhangsan", "123456");

        mvc.perform(post("/api/tasks/" + taskId + "/reports").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content", "不应写入", "progress", 50))))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("任务不存在"));
        assertEquals(0, reportCount(taskId));
    }

    /** P1：删除与提交并发。无论谁先完成，最终不允许"已删除任务仍带 PENDING 汇报" */
    @Test
    void concurrentSubmitAndDeleteNoOrphanPending() throws Exception {
        long taskId = createTaskWithMember();
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
                MvcResult res = mvc.perform(post("/api/tasks/" + taskId + "/reports")
                                .header("Authorization", "Bearer " + zhangToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(Map.of("content", "并发提交", "progress", 50))))
                        .andReturn();
                JsonNode body = om.readTree(res.getResponse().getContentAsString());
                codes.add(body.get("code").asInt());
                // API 成功创建的报告必须登记进 createdReportIds：
                // 否则 cleanup 按已记录 id 删除后，report/history 随 member/task 被删而成为孤儿
                if (body.get("code").asInt() == 0 && body.get("data").isIntegralNumber()) {
                    createdReportIds.add(body.get("data").asLong());
                }
            } catch (Exception ignored) { }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "并发请求未在超时内结束");
        assertEquals(2, codes.size(), "两个请求都必须完成");

        int deleted = jdbcTemplate.queryForObject("SELECT deleted FROM task WHERE id = ?", Integer.class, taskId);
        if (deleted == 1) {
            assertEquals(0, pendingCount(taskId), "已删除任务绝不能残留 PENDING 汇报");
        } else {
            assertEquals(1, pendingCount(taskId), "任务未删除则提交成功应恰好一条 PENDING");
        }
    }

    /** P1：未授权用户编辑/重提先返回权限错误，不泄露汇报状态 */
    @Test
    void nonOwnerUpdateAndResubmitReturn403First() throws Exception {
        long taskId = createTaskWithMember();
        Report r = insertReport(taskId, ReportStatus.WITHDRAWN);
        String token = login("wangwu", "123456");

        mvc.perform(put("/api/reports/" + r.getId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content", "不应生效", "progress", 60))))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权操作该汇报"));
        mvc.perform(post("/api/reports/" + r.getId() + "/resubmit").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权操作该汇报"));
        assertEquals(ReportStatus.WITHDRAWN, reportMapper.selectById(r.getId()).getStatus());
        assertEquals("待审唯一性汇报", reportMapper.selectById(r.getId()).getContent());
    }
}
