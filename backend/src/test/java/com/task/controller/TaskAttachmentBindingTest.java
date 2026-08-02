package com.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 规格：创建任务时绑定附件必须校验 —— 文件必须存在、必须是当前创建者上传的、
 * 未绑定其他任务、且同一请求内不重复；任一非法则整个任务创建失败（无 task/member/attachment 写入）。
 * fixture 自建并只清理本用例数据，MinIO 全部 @MockBean。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class TaskAttachmentBindingTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean MinioClient minioClient;

    /** 自建数据 id（按依赖顺序物理清理：task_attachment → task → minio_file） */
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdFileIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdTaskIds) {
            jdbcTemplate.update("DELETE FROM task_member WHERE task_id = ?", id);
            jdbcTemplate.update("DELETE FROM task_attachment WHERE task_id = ?", id);
            jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        }
        for (Long id : createdFileIds) jdbcTemplate.update("DELETE FROM minio_file WHERE id = ?", id);
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

    private long insertMinioFile(String objectName, long uploaderId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO minio_file (object_name, file_name, size, uploader_id) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, objectName);
            ps.setString(2, "绑定测试.txt");
            ps.setLong(3, 123L);
            ps.setLong(4, uploaderId);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        createdFileIds.add(id);
        return id;
    }

    private void insertBoundAttachment(long taskId, long fileId, long uploadedBy) {
        jdbcTemplate.update("INSERT INTO task_attachment (task_id, minio_file_id, file_name, file_url, file_size, uploaded_by) VALUES (?,?,?,?,?,?)",
                taskId, fileId, "已绑定.txt", "http://minio.test/" + fileId, 123L, uploadedBy);
    }

    private long insertTaskRow(String name) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task (name, creator_id, assign_type, assignee_id) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setLong(2, userId("leader1"));
            ps.setString(3, "INDIVIDUAL");
            ps.setLong(4, userId("lisi"));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        createdTaskIds.add(id);
        return id;
    }

    private int count(String table, String column, Object value) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }

    /** 发起创建任务请求，name 唯一便于断言"整件事务未写入" */
    private void postCreate(String token, String name, List<Long> attachmentIds, int expectCode) throws Exception {
        mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", name,
                                "assignType", "INDIVIDUAL",
                                "assigneeId", userId("lisi"),
                                "attachmentIds", attachmentIds == null ? List.of() : attachmentIds))))
                .andExpect(jsonPath("$.code").value(expectCode));
    }

    /** 文件 id 不存在（缺失）：400，整件事务回滚 */
    @Test
    void missingAttachmentIdFailsCreation() throws Exception {
        String token = login("leader1", "123456");
        String name = "绑定缺失-" + UUID.randomUUID();
        postCreate(token, name, List.of(999999L), 400);
        assertEquals(0, count("task", "name", name), "任务不应创建");
        assertEquals(0, count("task_attachment", "minio_file_id", 999999L), "不应产生附件绑定");
    }

    /** 附件列表含 null id：400，不能在排序阶段落成 500 */
    @Test
    void nullAttachmentIdFailsCreation() throws Exception {
        String token = login("leader1", "123456");
        String name = "绑定空 ID-" + UUID.randomUUID();
        List<Long> ids = new ArrayList<>();
        ids.add(null);
        postCreate(token, name, ids, 400);
        assertEquals(0, count("task", "name", name), "任务不应创建");
    }

    /** 使用他人上传的文件：400，整件事务回滚 */
    @Test
    void otherUploadersFileFailsCreation() throws Exception {
        long leaderId = userId("leader1");
        long zhangsanId = userId("zhangsan");
        String token = login("leader1", "123456");
        long otherFile = insertMinioFile("other-uploader-" + UUID.randomUUID(), zhangsanId);
        String name = "绑定他人文件-" + UUID.randomUUID();

        postCreate(token, name, List.of(otherFile), 400);
        assertEquals(0, count("task", "name", name), "任务不应创建");
        assertEquals(0, count("task_attachment", "minio_file_id", otherFile), "不应产生附件绑定");
        assertEquals(1, count("minio_file", "id", otherFile), "文件行应保留");
        assertEquals(leaderId, userId("leader1"), "leader1 未上传该文件");
    }

    /** 文件已绑定其他任务：400，整件事务回滚，原绑定保留 */
    @Test
    void alreadyBoundFileFailsCreation() throws Exception {
        long leaderId = userId("leader1");
        String token = login("leader1", "123456");
        long boundFile = insertMinioFile("already-bound-" + UUID.randomUUID(), leaderId);
        long otherTaskId = insertTaskRow("已绑定任务-" + UUID.randomUUID());
        insertBoundAttachment(otherTaskId, boundFile, leaderId);
        String name = "绑定已占用-" + UUID.randomUUID();

        postCreate(token, name, List.of(boundFile), 400);
        assertEquals(0, count("task", "name", name), "任务不应创建");
        assertEquals(1, count("task_attachment", "minio_file_id", boundFile), "原绑定应保留且无新绑定");
    }

    /** 同一请求内重复文件 id：400，整件事务回滚 */
    @Test
    void duplicateAttachmentIdsFailCreation() throws Exception {
        long leaderId = userId("leader1");
        String token = login("leader1", "123456");
        long file = insertMinioFile("dup-" + UUID.randomUUID(), leaderId);
        String name = "绑定重复-" + UUID.randomUUID();

        postCreate(token, name, List.of(file, file), 400);
        assertEquals(0, count("task", "name", name), "任务不应创建");
        assertEquals(0, count("task_attachment", "minio_file_id", file), "不应产生附件绑定");
    }

    /** 合法且归自己所有的文件：code=0，附件绑定成功且记录 minio_file_id 与真实上传者 */
    @Test
    void validOwnedIdsCreateTaskWithAttachments() throws Exception {
        long leaderId = userId("leader1");
        String token = login("leader1", "123456");
        long f1 = insertMinioFile("valid-1-" + UUID.randomUUID(), leaderId);
        long f2 = insertMinioFile("valid-2-" + UUID.randomUUID(), leaderId);
        String name = "绑定合法-" + UUID.randomUUID();

        String body = mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "name", name,
                                "assignType", "INDIVIDUAL",
                                "assigneeId", userId("lisi"),
                                "attachmentIds", List.of(f1, f2)))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long taskId = Long.parseLong(body.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdTaskIds.add(taskId);
        // 两条附件记录，minio_file_id 与真实上传者都正确回填
        assertEquals(2, count("task_attachment", "task_id", taskId), "应绑定两条附件");
        List<Long> bound = jdbcTemplate.queryForList(
                        "SELECT minio_file_id, uploaded_by FROM task_attachment WHERE task_id = ?", taskId)
                .stream().map(r -> r.get("minio_file_id") == null ? -1L : ((Number) r.get("minio_file_id")).longValue())
                .sorted().toList();
        assertEquals(List.of(f1, f2), bound, "minio_file_id 应一一对应");
        List<Number> uploaders = jdbcTemplate.queryForList(
                        "SELECT uploaded_by FROM task_attachment WHERE task_id = ?", taskId)
                .stream().map(r -> (Number) r.get("uploaded_by")).toList();
        for (Number u : uploaders) {
            assertEquals(leaderId, u.longValue(), "上传者应为真实上传人");
        }
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_attachment ta JOIN minio_file mf ON ta.minio_file_id = mf.id "
                        + "WHERE ta.task_id = ? AND ta.uploaded_by = mf.uploader_id", Integer.class, taskId),
                "附件上传者快照应与文件真实上传者一致");
    }
}
