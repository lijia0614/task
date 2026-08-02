package com.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 规格：任务只能由创建者本人删除（管理员也不行）；删除前有附件必须清理
 * MinIO 对象并删除 attachment/minio 行；待审核汇报存在时拒绝删除；
 * MinIO 删除失败时任务与数据库行全部保留。
 * fixture 自建并只清理本用例数据，MinIO 全部 @MockBean。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class TaskDeletionOwnershipTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean MinioClient minioClient;
    @Value("${minio.bucket}") String bucket;

    /** 自建数据 id（按依赖顺序物理清理：report → task_member → task_attachment → task → minio_file） */
    private final List<Long> createdReportIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdFileIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdReportIds) jdbcTemplate.update("DELETE FROM report WHERE id = ?", id);
        for (Long id : createdTaskIds) {
            jdbcTemplate.update("DELETE FROM task_member WHERE task_id = ?", id);
            jdbcTemplate.update("DELETE FROM task_attachment WHERE task_id = ?", id);
            jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        }
        for (Long id : createdFileIds) jdbcTemplate.update("DELETE FROM minio_file WHERE id = ?", id);
        createdReportIds.clear();
        createdTaskIds.clear();
        createdFileIds.clear();
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

    /** leader1 创建个人任务（assigneeId 指定），返回 task id */
    private long createTask(String token, long assigneeId) throws Exception {
        String body = mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", "删除归属测试任务-" + UUID.randomUUID(),
                                "assignType", "INDIVIDUAL",
                                "assigneeId", assigneeId))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long taskId = Long.parseLong(body.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdTaskIds.add(taskId);
        return taskId;
    }

    private long insertMinioFile(String objectName, long uploaderId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO minio_file (object_name, file_name, size, uploader_id) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, objectName);
            ps.setString(2, "任务附件.txt");
            ps.setLong(3, 123L);
            ps.setLong(4, uploaderId);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        createdFileIds.add(id);
        return id;
    }

    private void insertAttachment(long taskId, long fileId, long uploadedBy) {
        jdbcTemplate.update("INSERT INTO task_attachment (task_id, minio_file_id, file_name, file_url, file_size, uploaded_by) VALUES (?,?,?,?,?,?)",
                taskId, fileId, "附件.txt", "http://minio.test/" + fileId, 123L, uploadedBy);
    }

    private void insertPendingReport(long taskMemberId, long userId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO report (task_member_id, user_id, content, progress) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, taskMemberId);
            ps.setLong(2, userId);
            ps.setString(3, "待审核汇报");
            ps.setInt(4, 30);
            return ps;
        }, kh);
        createdReportIds.add(kh.getKey().longValue());
    }

    private int deletedFlag(long taskId) {
        return jdbcTemplate.queryForObject("SELECT deleted FROM task WHERE id = ?", Integer.class, taskId);
    }

    private int count(String table, String column, long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
    }

    /** 创建者删除自己的任务：code=0，逻辑删除，附件与 minio 行清除，所有 MinIO 对象按 bucket/object 删除 */
    @Test
    void creatorDeletesOwnTaskRemovesAttachmentsAndMinioObjects() throws Exception {
        long leaderId = userId("leader1");
        long lisiId = userId("lisi");
        String token = login("leader1", "123456");
        long taskId = createTask(token, lisiId);
        String obj1 = "del-1-" + UUID.randomUUID();
        String obj2 = "del-2-" + UUID.randomUUID();
        long f1 = insertMinioFile(obj1, leaderId);
        long f2 = insertMinioFile(obj2, leaderId);
        insertAttachment(taskId, f1, leaderId);
        insertAttachment(taskId, f2, leaderId);

        mvc.perform(delete("/api/tasks/" + taskId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(1, deletedFlag(taskId), "任务应被逻辑删除");
        assertEquals(0, count("task_attachment", "task_id", taskId), "附件行应删除");
        assertEquals(0, count("minio_file", "id", f1), "minio_file 行应删除");
        assertEquals(0, count("minio_file", "id", f2), "minio_file 行应删除");
        verify(minioClient).removeObject(argThat(a -> bucket.equals(a.bucket()) && obj1.equals(a.object())));
        verify(minioClient).removeObject(argThat(a -> bucket.equals(a.bucket()) && obj2.equals(a.object())));
    }

    /** 管理员不能删除他人的任务：403，任务保留 */
    @Test
    void adminCannotDeleteOthersTask() throws Exception {
        long lisiId = userId("lisi");
        String leaderToken = login("leader1", "123456");
        long taskId = createTask(leaderToken, lisiId);
        String adminToken = login("admin", "admin123");

        mvc.perform(delete("/api/tasks/" + taskId).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(0, deletedFlag(taskId), "任务应保留");
    }

    /** 其他员工不能删除他人任务：403，任务保留 */
    @Test
    void otherUserCannotDeleteTask() throws Exception {
        long lisiId = userId("lisi");
        String leaderToken = login("leader1", "123456");
        long taskId = createTask(leaderToken, lisiId);
        String lisiToken = login("lisi", "123456");

        mvc.perform(delete("/api/tasks/" + taskId).header("Authorization", "Bearer " + lisiToken))
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(0, deletedFlag(taskId), "任务应保留");
    }

    /** 存在待审核汇报时拒绝删除：400，任务保留 */
    @Test
    void pendingReportRejectsDeletion() throws Exception {
        long lisiId = userId("lisi");
        String token = login("leader1", "123456");
        long taskId = createTask(token, lisiId);
        long memberId = jdbcTemplate.queryForObject("SELECT id FROM task_member WHERE task_id = ?", Long.class, taskId);
        insertPendingReport(memberId, lisiId);

        mvc.perform(delete("/api/tasks/" + taskId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(0, deletedFlag(taskId), "任务应保留");
    }

    /** MinIO 删除失败：400，任务与附件/minio 行全部保留（不允许部分清理） */
    @Test
    void minioFailureLeavesTaskAndDatabaseRows() throws Exception {
        long leaderId = userId("leader1");
        long lisiId = userId("lisi");
        String token = login("leader1", "123456");
        long taskId = createTask(token, lisiId);
        long f1 = insertMinioFile("del-fail-" + UUID.randomUUID(), leaderId);
        insertAttachment(taskId, f1, leaderId);
        doThrow(new RuntimeException("minio down")).when(minioClient).removeObject(argThat(a -> true));

        mvc.perform(delete("/api/tasks/" + taskId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(0, deletedFlag(taskId), "任务应保留");
        assertEquals(1, count("task_attachment", "task_id", taskId), "附件行应保留");
        assertEquals(1, count("minio_file", "id", f1), "minio_file 行应保留");
    }
}
