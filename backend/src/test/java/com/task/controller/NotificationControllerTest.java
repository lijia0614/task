package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.Notification;
import com.task.entity.SysUser;
import com.task.mapper.NotificationMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 站内信契约（Task 21）：触发/跳过自我通知/未读与已读/分页/越权 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class NotificationControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired NotificationMapper notificationMapper;
    @Autowired SysUserMapper userMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 自建数据 id（依赖顺序清理：notification → report → task_member → task → sys_group） */
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<Long> createdGroupIds = new ArrayList<>();
    /** 测试组加入成员前快照的 (userId, 原 groupId)，组删除后恢复种子用户，避免 group_id 指向已删组 */
    private final List<Object[]> groupRestore = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!createdTaskIds.isEmpty()) {
            String inIds = String.join(",", createdTaskIds.stream().map(String::valueOf).toList());
            jdbcTemplate.update("DELETE FROM notification WHERE task_id IN (" + inIds + ")");
            jdbcTemplate.update("DELETE FROM report WHERE task_member_id IN (SELECT id FROM task_member WHERE task_id IN (" + inIds + "))");
        }
        for (Long id : createdMemberIds) jdbcTemplate.update("DELETE FROM task_member WHERE id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        for (Long id : createdGroupIds) jdbcTemplate.update("DELETE FROM sys_group WHERE id = ?", id);
        for (Object[] row : groupRestore) jdbcTemplate.update("UPDATE sys_user SET group_id = ? WHERE id = ?", row[1], row[0]);
        createdTaskIds.clear();
        createdMemberIds.clear();
        createdGroupIds.clear();
        groupRestore.clear();
    }

    private String login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private long userId(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)).getId();
    }

    /** GET /api/notifications/unread-count，返回当前未读数 */
    private long unreadCount(String token) throws Exception {
        return Long.parseLong(mvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"count\":(\\d+).*", "$1"));
    }

    private long createTask(String token, String name, String assignType, Long assigneeId, String weightsJson) throws Exception {
        String body = mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"assignType\":\"" + assignType
                                + "\",\"assigneeId\":" + assigneeId
                                + (weightsJson == null ? "" : ",\"weights\":" + weightsJson) + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long taskId = Long.parseLong(body.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdTaskIds.add(taskId);
        memberMapper.selectList(new LambdaQueryWrapper<com.task.entity.TaskMember>()
                        .eq(com.task.entity.TaskMember::getTaskId, taskId))
                .forEach(tm -> createdMemberIds.add(tm.getId()));
        return taskId;
    }

    /** leader1 建组（组长 leader1 本人），并把 leader1/zhangsan/wangwu 都加入组，返回 groupId */
    private long createGroupWithAllThree() throws Exception {
        String token = login("leader1", "123456");
        String body = mvc.perform(post("/api/groups").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"通知测试组" + System.currentTimeMillis() + "\",\"leaderId\":" + userId("leader1") + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long gid = Long.parseLong(body.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdGroupIds.add(gid);
        for (String uname : new String[]{"leader1", "zhangsan", "wangwu"}) {
            long uid = userId(uname);
            groupRestore.add(new Object[]{uid, userMapper.selectById(uid).getGroupId()});
            mvc.perform(post("/api/groups/" + gid + "/members").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + uid + "}"))
                    .andExpect(jsonPath("$.code").value(0));
        }
        return gid;
    }

    private long submitReport(String token, long taskId, int progress) throws Exception {
        String body = mvc.perform(post("/api/tasks/" + taskId + "/reports").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("content", "通知测试汇报", "progress", progress))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"data\":(\\d+).*", "$1"));
    }

    private List<Notification> notificationsOf(long taskId) {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getTaskId, taskId));
    }

    /** 个人任务：被分配者收到 TASK_ASSIGNED，创建者不收到 */
    @Test
    void individualTaskAssignNotifiesAssigneeOnly() throws Exception {
        String token = login("leader1", "123456");
        long taskId = createTask(token, "通知个人任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        List<Notification> ns = notificationsOf(taskId);
        assertEquals(1, ns.size(), "只有被分配者收到");
        assertEquals(userId("zhangsan"), ns.get(0).getUserId());
        assertEquals("TASK_ASSIGNED", ns.get(0).getType());
    }

    /** 小组任务：除创建者（组长也是成员）外的成员收到 */
    @Test
    void groupTaskAssignNotifiesMembersExceptCreator() throws Exception {
        String token = login("leader1", "123456");
        long gid = createGroupWithAllThree();
        long taskId = createTask(token, "通知小组任务" + System.currentTimeMillis(), "GROUP", gid, "[50,30,20]");
        List<Notification> ns = notificationsOf(taskId);
        assertEquals(2, ns.size(), "zhangsan/wangwu 收到，组长本人跳过");
        List<Long> receivers = ns.stream().map(Notification::getUserId).toList();
        assertTrue(receivers.contains(userId("zhangsan")));
        assertTrue(receivers.contains(userId("wangwu")));
        assertFalse(receivers.contains(userId("leader1")));
    }

    /** 提交汇报：任务创建者收到 REPORT_SUBMITTED，提交人自己不收到 */
    @Test
    void reportSubmitNotifiesTaskCreatorNotSelf() throws Exception {
        String leaderToken = login("leader1", "123456");
        long taskId = createTask(leaderToken, "通知提交任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        submitReport(login("zhangsan", "123456"), taskId, 30);
        List<Notification> ns = notificationsOf(taskId).stream()
                .filter(n -> "REPORT_SUBMITTED".equals(n.getType())).toList();
        assertEquals(1, ns.size());
        assertEquals(userId("leader1"), ns.get(0).getUserId());
    }

    /** 审核通过：提交人收到 REPORT_APPROVED（含最终进度文案） */
    @Test
    void reportApproveNotifiesSubmitter() throws Exception {
        String leaderToken = login("leader1", "123456");
        long taskId = createTask(leaderToken, "通知通过任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        long reportId = submitReport(login("zhangsan", "123456"), taskId, 40);
        mvc.perform(post("/api/reports/" + reportId + "/approve").header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("progress", 50, "reviewComment", "通过"))))
                .andExpect(jsonPath("$.code").value(0));
        Notification n = notificationsOf(taskId).stream()
                .filter(x -> "REPORT_APPROVED".equals(x.getType())).findFirst().orElse(null);
        assertNotNull(n, "提交人应收到通过通知");
        assertEquals(userId("zhangsan"), n.getUserId());
        assertTrue(n.getContent().contains("最终进度 50%"));
    }

    /** 驳回：提交人收到 REPORT_REJECTED（含理由） */
    @Test
    void reportRejectNotifiesSubmitterWithReason() throws Exception {
        String leaderToken = login("leader1", "123456");
        long taskId = createTask(leaderToken, "通知驳回任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        long reportId = submitReport(login("zhangsan", "123456"), taskId, 40);
        mvc.perform(post("/api/reports/" + reportId + "/reject").header("Authorization", "Bearer " + leaderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("reviewComment", "格式不符合要求"))))
                .andExpect(jsonPath("$.code").value(0));
        Notification n = notificationsOf(taskId).stream()
                .filter(x -> "REPORT_REJECTED".equals(x.getType())).findFirst().orElse(null);
        assertNotNull(n);
        assertEquals(userId("zhangsan"), n.getUserId());
        assertTrue(n.getContent().contains("格式不符合要求"));
    }

    /** 重提汇报：创建者再收一条 REPORT_SUBMITTED（共 2 条） */
    @Test
    void resubmitNotifiesCreatorAgain() throws Exception {
        String leaderToken = login("leader1", "123456");
        long taskId = createTask(leaderToken, "通知重提任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        String zToken = login("zhangsan", "123456");
        long reportId = submitReport(zToken, taskId, 30);
        mvc.perform(post("/api/reports/" + reportId + "/withdraw").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/api/reports/" + reportId + "/resubmit").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0));
        long count = notificationsOf(taskId).stream().filter(n -> "REPORT_SUBMITTED".equals(n.getType())).count();
        assertEquals(2, count);
    }

    /** 未读数 + 单条已读幂等（基线相对，不依赖全局精确计数） */
    @Test
    void unreadCountAndMarkReadIdempotent() throws Exception {
        String leaderToken = login("leader1", "123456");
        String zToken = login("zhangsan", "123456");
        long base = unreadCount(zToken); // 建任务前的 zhangsan 未读基线
        long taskId = createTask(leaderToken, "通知已读任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        assertEquals(base + 1, unreadCount(zToken), "分配通知应计入未读");
        long nid = notificationsOf(taskId).get(0).getId();
        mvc.perform(put("/api/notifications/" + nid + "/read").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/notifications/" + nid + "/read").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0)); // 幂等
        assertEquals(base, unreadCount(zToken), "已读后未读回到基线");
    }

    /** 全部已读只清自己的：leader1 的未读不得被 zhangsan 的 read-all 误清（基线相对） */
    @Test
    void markAllReadClearsUnread() throws Exception {
        String leaderToken = login("leader1", "123456");
        String zToken = login("zhangsan", "123456");
        long leaderBase = unreadCount(leaderToken);
        long taskId = createTask(leaderToken, "通知全读任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        submitReport(zToken, taskId, 30); // 给 leader1 制造一条未读（REPORT_SUBMITTED）
        assertEquals(leaderBase + 1, unreadCount(leaderToken), "汇报应给 leader1 制造一条未读");
        mvc.perform(put("/api/notifications/read-all").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(0, unreadCount(zToken), "zhangsan 全部未读（含历史残量）清零");
        assertEquals(leaderBase + 1, unreadCount(leaderToken), "read-all 不得清掉 leader1 的未读");
    }

    /** 列表只见自己的 + 越权 read 他人通知 404 + 分页校验 */
    @Test
    void listOwnershipPaginationAndForbiddenRead() throws Exception {
        String leaderToken = login("leader1", "123456");
        long taskId = createTask(leaderToken, "通知越权任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        long nid = notificationsOf(taskId).get(0).getId(); // 属于 zhangsan
        // 越权：leader1 读 zhangsan 的通知 → 404
        mvc.perform(put("/api/notifications/" + nid + "/read").header("Authorization", "Bearer " + leaderToken))
                .andExpect(jsonPath("$.code").value(404));
        // zhangsan 列表：id 倒序最新一条即本测试通知；leader1 列表不得包含该通知
        String zToken = login("zhangsan", "123456");
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].id").value(nid));
        String leaderListBody = mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + leaderToken))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        for (com.fasterxml.jackson.databind.JsonNode node : om.readTree(leaderListBody).path("data").path("records")) {
            assertTrue(node.path("id").asLong() != nid, "他人的通知不能出现在我的列表");
        }
        // 分页参数校验
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + zToken).param("page", "0"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + zToken).param("size", "1001"))
                .andExpect(jsonPath("$.code").value(400));
    }
}
