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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 规格：附件删除只允许上传者本人，且必须清理 MinIO 对象；
 * 已绑定任务的附件不允许直接删除；MinIO 删除失败时数据库行保留。
 * fixture 直接插入并只清理本用例自建数据，MinIO 全部走 @MockBean，绝不触碰真实对象。
 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class FileOwnershipTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean MinioClient minioClient;
    @Value("${minio.bucket}") String bucket;

    /** 本用例自建数据 id（按依赖顺序物理清理：task_attachment → task → minio_file） */
    private final List<Long> createdAttachmentIds = new ArrayList<>();
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdFileIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdAttachmentIds) jdbcTemplate.update("DELETE FROM task_attachment WHERE id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        for (Long id : createdFileIds) jdbcTemplate.update("DELETE FROM minio_file WHERE id = ?", id);
        createdAttachmentIds.clear();
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
            ps.setString(2, "测试文件.txt");
            ps.setLong(3, 123L);
            ps.setLong(4, uploaderId);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        createdFileIds.add(id);
        return id;
    }

    private long insertTask(long creatorId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task (name, creator_id, assign_type, assignee_id) VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "附件归属测试任务-" + UUID.randomUUID());
            ps.setLong(2, creatorId);
            ps.setString(3, "INDIVIDUAL");
            ps.setLong(4, creatorId);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        createdTaskIds.add(id);
        return id;
    }

    private long insertAttachment(long taskId, long fileId, long uploadedBy) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO task_attachment (task_id, minio_file_id, file_name, file_url, file_size, uploaded_by) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, taskId);
            ps.setLong(2, fileId);
            ps.setString(3, "绑定文件.txt");
            ps.setString(4, "http://minio.test/" + fileId);
            ps.setLong(5, 456L);
            ps.setLong(6, uploadedBy);
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        createdAttachmentIds.add(id);
        return id;
    }

    private int count(String table, String column, long id) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
    }

    /** 上传者删除自己的未绑定文件：code=0，且 MinIO 对象被按 bucket/object 删除 */
    @Test
    void uploaderDeletesOwnUnboundFileAndMinioObject() throws Exception {
        long uploaderId = userId("zhangsan");
        String objectName = "own-" + UUID.randomUUID();
        long fileId = insertMinioFile(objectName, uploaderId);
        String token = login("zhangsan", "123456");

        mvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, count("minio_file", "id", fileId), "数据库行应被删除");
        verify(minioClient).removeObject(argThat(a -> bucket.equals(a.bucket()) && objectName.equals(a.object())));
    }

    /** 其他员工删除他人的文件：403，行保留，MinIO 删除必须从未发生 */
    @Test
    void otherUserCannotDeleteFile() throws Exception {
        long uploaderId = userId("zhangsan");
        String objectName = "other-" + UUID.randomUUID();
        long fileId = insertMinioFile(objectName, uploaderId);
        String otherToken = login("lisi", "123456");

        mvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(1, count("minio_file", "id", fileId), "数据库行应保留");
        verify(minioClient, never()).removeObject(argThat(a -> objectName.equals(a.object())));
    }

    /** 管理员也不能删除他人文件：403（归属权仅属于上传者本人） */
    @Test
    void adminCannotDeleteAnotherUsersFile() throws Exception {
        long uploaderId = userId("zhangsan");
        String objectName = "admin-" + UUID.randomUUID();
        long fileId = insertMinioFile(objectName, uploaderId);
        String adminToken = login("admin", "admin123");

        mvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + adminToken))
                .andExpect(jsonPath("$.code").value(403));
        assertEquals(1, count("minio_file", "id", fileId), "数据库行应保留");
    }

    /** 已绑定任务的附件不允许直接删除：400，minio_file 与 task_attachment 都保留 */
    @Test
    void boundFileCannotBeDeletedDirectly() throws Exception {
        long uploaderId = userId("zhangsan");
        long taskId = insertTask(uploaderId);
        long fileId = insertMinioFile("bound-" + UUID.randomUUID(), uploaderId);
        long attachmentId = insertAttachment(taskId, fileId, uploaderId);
        String token = login("zhangsan", "123456");

        mvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(1, count("minio_file", "id", fileId), "minio_file 行应保留");
        assertEquals(1, count("task_attachment", "id", attachmentId), "task_attachment 行应保留");
    }

    /** MinIO 删除失败：400，且数据库行保留（不允许产生孤儿引用） */
    @Test
    void minioFailureKeepsDatabaseRow() throws Exception {
        long uploaderId = userId("zhangsan");
        long fileId = insertMinioFile("fail-" + UUID.randomUUID(), uploaderId);
        doThrow(new RuntimeException("minio down")).when(minioClient).removeObject(argThat(a -> true));
        String token = login("zhangsan", "123456");

        mvc.perform(delete("/api/files/" + fileId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(1, count("minio_file", "id", fileId), "MinIO 失败时数据库行必须保留");
    }
}
