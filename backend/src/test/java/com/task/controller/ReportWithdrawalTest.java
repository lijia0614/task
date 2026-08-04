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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 规格：汇报撤回状态机 PENDING→WITHDRAWN→(编辑)→PENDING；
 * 仅提交人可撤回/编辑/重提；WITHDRAWN 仅本人可见并从待审列表消失；
 * 关联评论不删除但撤回期间不向他人暴露；report_history 记录全部动作与快照。
 * fixture 自建并只清理本用例数据，MinIO 全部 @MockBean。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class ReportWithdrawalTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired SysUserMapper userMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired ReportMapper reportMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean MinioClient minioClient;

    /** 自建数据 id（按依赖顺序物理清理：report_history → comment → report → task_member → task） */
    private final List<Long> createdReportIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdReportIds) {
            jdbcTemplate.update("DELETE FROM report_history WHERE report_id = ?", id);
            jdbcTemplate.update("DELETE FROM comment WHERE report_id = ?", id);
        }
        for (Long id : createdReportIds) jdbcTemplate.update("DELETE FROM report WHERE id = ?", id);
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

    /** 自造数据：任务(creator=leader1) + 成员(zhangsan, 指定进度) + 一条汇报（可带审核字段脏数据） */
    private Report createReport(ReportStatus status, int memberProgress, Report fill) {
        long leaderId = userId("leader1");
        long zhangsanId = userId("zhangsan");
        Task task = new Task();
        task.setName("撤回状态机测试-" + System.currentTimeMillis());
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
        r.setContent("撤回测试汇报");
        r.setProgress(50);
        r.setStatus(status);
        if (fill != null) {
            r.setReviewerId(fill.getReviewerId());
            r.setReviewComment(fill.getReviewComment());
            r.setReviewedAt(fill.getReviewedAt());
            r.setFinalProgress(fill.getFinalProgress());
        }
        reportMapper.insert(r);
        createdReportIds.add(r.getId());
        return r;
    }

    private Report createReport(ReportStatus status, int memberProgress) {
        return createReport(status, memberProgress, null);
    }

    /** 只建任务+成员（不插汇报），返回 task id —— 供走真实提交接口的用例 */
    private long createTaskWithMember(int memberProgress) {
        long leaderId = userId("leader1");
        long zhangsanId = userId("zhangsan");
        Task task = new Task();
        task.setName("撤回状态机测试-" + System.currentTimeMillis());
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
        return task.getId();
    }

    /** 向指定任务追加一条汇报（同一任务下多条汇报的可见性场景） */
    private Report insertReportOn(long taskId, ReportStatus status) {
        long zhangsanId = userId("zhangsan");
        long memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM task_member WHERE task_id = ?", Long.class, taskId);
        Report r = new Report();
        r.setTaskMemberId(memberId);
        r.setUserId(zhangsanId);
        r.setContent("同任务汇报");
        r.setProgress(50);
        r.setStatus(status);
        reportMapper.insert(r);
        createdReportIds.add(r.getId());
        return r;
    }

    private void insertComment(long reportId, long userId, String content) {
        jdbcTemplate.update("INSERT INTO comment (report_id, parent_id, user_id, content) VALUES (?,0,?,?)",
                reportId, userId, content);
    }

    private int historyCount(long reportId, String action) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM report_history WHERE report_id = ? AND action = ?", Integer.class, reportId, action);
    }

    private List<Map<String, Object>> historyOf(long reportId) {
        return jdbcTemplate.queryForList(
                "SELECT action, content, progress, actor_id FROM report_history WHERE report_id = ? ORDER BY id", reportId);
    }

    /** 提交人撤回 PENDING：code=0，状态转 WITHDRAWN，写历史，从审核人待审列表消失 */
    @Test
    void ownerCanWithdrawPendingReport() throws Exception {
        Report r = createReport(ReportStatus.PENDING, 0);
        String token = login("zhangsan", "123456");
        long zhangsanId = userId("zhangsan");

        mvc.perform(post("/api/reports/" + r.getId() + "/withdraw").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(ReportStatus.WITHDRAWN, reportMapper.selectById(r.getId()).getStatus());
        assertEquals(1, historyCount(r.getId(), "WITHDRAWN"));
        List<Map<String, Object>> h = historyOf(r.getId());
        assertEquals("撤回测试汇报", h.get(h.size() - 1).get("content"));
        assertEquals(50, ((Number) h.get(h.size() - 1).get("progress")).intValue());
        assertEquals(zhangsanId, ((Number) h.get(h.size() - 1).get("actor_id")).longValue());
        // 审核人待审列表不再包含
        String leaderToken = login("leader1", "123456");
        mvc.perform(get("/api/reports/pending").header("Authorization", "Bearer " + leaderToken))
                .andExpect(jsonPath("$.data[?(@.id == " + r.getId() + ")]").isEmpty());
    }

    /** 非提交人撤回：一律 403 权限错误，不泄露汇报状态（PENDING/APPROVED 同样处理） */
    @Test
    void nonOwnerCannotWithdrawAndNoStatusLeak() throws Exception {
        Report pending = createReport(ReportStatus.PENDING, 0);
        Report approved = createReport(ReportStatus.APPROVED, 0);
        String token = login("wangwu", "123456");

        mvc.perform(post("/api/reports/" + pending.getId() + "/withdraw").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权操作该汇报"));
        mvc.perform(post("/api/reports/" + approved.getId() + "/withdraw").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权操作该汇报"));
        assertEquals(ReportStatus.PENDING, reportMapper.selectById(pending.getId()).getStatus());
        assertEquals(ReportStatus.APPROVED, reportMapper.selectById(approved.getId()).getStatus());
    }

    /** 已审核汇报不能被撤回：400 状态已变化 */
    @Test
    void cannotWithdrawReviewedReport() throws Exception {
        Report r = createReport(ReportStatus.APPROVED, 0);
        String token = login("zhangsan", "123456");
        mvc.perform(post("/api/reports/" + r.getId() + "/withdraw").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("汇报状态已变化，请刷新"));
    }

    /** WITHDRAWN 编辑：code=0，状态不变，内容/进度更新，写 EDITED 历史 */
    @Test
    void ownerCanEditWithdrawnReport() throws Exception {
        Report r = createReport(ReportStatus.WITHDRAWN, 0);
        String token = login("zhangsan", "123456");
        long zhangsanId = userId("zhangsan");

        mvc.perform(put("/api/reports/" + r.getId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content", "编辑后的内容", "progress", 60))))
                .andExpect(jsonPath("$.code").value(0));
        Report after = reportMapper.selectById(r.getId());
        assertEquals(ReportStatus.WITHDRAWN, after.getStatus());
        assertEquals("编辑后的内容", after.getContent());
        assertEquals(60, after.getProgress());
        assertEquals(1, historyCount(r.getId(), "EDITED"));
        List<Map<String, Object>> h = historyOf(r.getId());
        assertEquals("编辑后的内容", h.get(h.size() - 1).get("content"));
        assertEquals(60, ((Number) h.get(h.size() - 1).get("progress")).intValue());
        assertEquals(zhangsanId, ((Number) h.get(h.size() - 1).get("actor_id")).longValue());
    }

    /** PENDING/APPROVED 不能编辑：400 状态已变化 */
    @Test
    void cannotEditPendingOrApprovedReport() throws Exception {
        Report pending = createReport(ReportStatus.PENDING, 0);
        Report approved = createReport(ReportStatus.APPROVED, 0);
        String token = login("zhangsan", "123456");
        String body = om.writeValueAsString(Map.of("content", "不应生效", "progress", 60));

        mvc.perform(put("/api/reports/" + pending.getId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("汇报状态已变化，请刷新"));
        mvc.perform(put("/api/reports/" + approved.getId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("汇报状态已变化，请刷新"));
        assertEquals("撤回测试汇报", reportMapper.selectById(pending.getId()).getContent());
    }

    /** 编辑进度不能低于成员当前进度 */
    @Test
    void editProgressBelowMemberProgressRejected() throws Exception {
        Report r = createReport(ReportStatus.WITHDRAWN, 40);
        String token = login("zhangsan", "123456");
        mvc.perform(put("/api/reports/" + r.getId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content", "回退进度", "progress", 30))))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("进度不能低于当前进度"));
    }

    /** WITHDRAWN 重新提交：code=0，转 PENDING，回到待审列表，写 RESUBMITTED 历史 */
    @Test
    void ownerCanResubmitWithdrawnReport() throws Exception {
        Report r = createReport(ReportStatus.WITHDRAWN, 0);
        String token = login("zhangsan", "123456");

        mvc.perform(post("/api/reports/" + r.getId() + "/resubmit").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(ReportStatus.PENDING, reportMapper.selectById(r.getId()).getStatus());
        assertEquals(1, historyCount(r.getId(), "RESUBMITTED"));
        String leaderToken = login("leader1", "123456");
        mvc.perform(get("/api/reports/pending").header("Authorization", "Bearer " + leaderToken))
                .andExpect(jsonPath("$.data[?(@.id == " + r.getId() + ")]").isNotEmpty());
    }

    /** 重新提交清空旧审核字段 */
    @Test
    void resubmitClearsReviewFields() throws Exception {
        Report dirty = new Report();
        dirty.setReviewerId(userId("leader1"));
        dirty.setReviewComment("旧审核意见");
        dirty.setReviewedAt(LocalDateTime.now());
        dirty.setFinalProgress(55);
        Report r = createReport(ReportStatus.WITHDRAWN, 0, dirty);
        String token = login("zhangsan", "123456");

        mvc.perform(post("/api/reports/" + r.getId() + "/resubmit").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        Report after = reportMapper.selectById(r.getId());
        assertNull(after.getReviewerId());
        assertNull(after.getReviewComment());
        assertNull(after.getReviewedAt());
        assertNull(after.getFinalProgress());
    }

    /** WITHDRAWN 仅提交人可见：审核人与其他成员不可见（同一任务下与 APPROVED 对比） */
    @Test
    void withdrawnReportVisibleOnlyToOwner() throws Exception {
        Report withdrawn = createReport(ReportStatus.WITHDRAWN, 0);
        long taskId = memberMapper.selectById(withdrawn.getTaskMemberId()).getTaskId();
        Report approved = insertReportOn(taskId, ReportStatus.APPROVED);

        String leaderToken = login("leader1", "123456");
        mvc.perform(get("/api/tasks/" + taskId + "/reports").header("Authorization", "Bearer " + leaderToken))
                .andExpect(jsonPath("$.data[?(@.id == " + withdrawn.getId() + ")]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.id == " + approved.getId() + ")]").isNotEmpty());
        String wangwuToken = login("wangwu", "123456");
        mvc.perform(get("/api/tasks/" + taskId + "/reports").header("Authorization", "Bearer " + wangwuToken))
                .andExpect(jsonPath("$.data[?(@.id == " + withdrawn.getId() + ")]").isEmpty());
        String ownerToken = login("zhangsan", "123456");
        mvc.perform(get("/api/tasks/" + taskId + "/reports").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.data[?(@.id == " + withdrawn.getId() + ")]").isNotEmpty());
    }

    /** 撤回汇报的评论不删除，但撤回期间仅提交人可见 */
    @Test
    void withdrawnReportCommentsHiddenFromOthers() throws Exception {
        Report r = createReport(ReportStatus.WITHDRAWN, 0);
        long zhangsanId = userId("zhangsan");
        insertComment(r.getId(), zhangsanId, "撤回前的评论");

        String ownerToken = login("zhangsan", "123456");
        mvc.perform(get("/api/reports/" + r.getId() + "/comments").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].content").value("撤回前的评论"));
        String leaderToken = login("leader1", "123456");
        mvc.perform(get("/api/reports/" + r.getId() + "/comments").header("Authorization", "Bearer " + leaderToken))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权查看该汇报的评论"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM comment WHERE report_id = ?", Integer.class, r.getId()));
    }

    /** 全序列 submit→withdraw→edit→resubmit→approve：历史动作与快照逐一正确，APPROVED 记 finalProgress */
    @Test
    void historyRecordsActionsAndSnapshots() throws Exception {
        long zhangsanId = userId("zhangsan");
        long leaderId = userId("leader1");
        long taskId = createTaskWithMember(0);
        String zhangToken = login("zhangsan", "123456");
        String leaderToken = login("leader1", "123456");
        // 通过真实提交接口创建，产生 SUBMITTED 历史
        String submitBody = mvc.perform(post("/api/tasks/" + taskId + "/reports")
                        .header("Authorization", "Bearer " + zhangToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content", "撤回测试汇报", "progress", 50))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long reportId = Long.parseLong(submitBody.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdReportIds.add(reportId);

        mvc.perform(post("/api/reports/" + reportId + "/withdraw").header("Authorization", "Bearer " + zhangToken))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/reports/" + reportId).header("Authorization", "Bearer " + zhangToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("content", "编辑后", "progress", 70))))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/reports/" + reportId + "/resubmit").header("Authorization", "Bearer " + zhangToken))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/reports/" + reportId + "/approve").header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("progress", 80, "reviewComment", "通过"))))
                .andExpect(jsonPath("$.code").value(0));

        List<Map<String, Object>> h = historyOf(reportId);
        List<String> actions = h.stream().map(x -> (String) x.get("action")).toList();
        assertEquals(List.of("SUBMITTED", "WITHDRAWN", "EDITED", "RESUBMITTED", "APPROVED"), actions);
        // SUBMITTED/WITHDRAWN 记请求进度 50；EDITED/RESUBMITTED 记 70；APPROVED 记审核采用的 finalProgress 80
        assertEquals(50, ((Number) h.get(0).get("progress")).intValue());
        assertEquals(50, ((Number) h.get(1).get("progress")).intValue());
        assertEquals(70, ((Number) h.get(2).get("progress")).intValue());
        assertEquals(70, ((Number) h.get(3).get("progress")).intValue());
        assertEquals(80, ((Number) h.get(4).get("progress")).intValue());
        // 操作者：提交/撤回/编辑/重提是提交人，审核是审核人
        for (int i = 0; i < 4; i++) assertEquals(zhangsanId, ((Number) h.get(i).get("actor_id")).longValue());
        assertEquals(leaderId, ((Number) h.get(4).get("actor_id")).longValue());
    }
}
