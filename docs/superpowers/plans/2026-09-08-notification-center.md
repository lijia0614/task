# 站内信（通知中心）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 任务流转关键事件（被分配任务/有汇报待审核/汇报通过/汇报驳回）以站内信送达：notification 表 + 四个触发插入点（同事务、跳过自我通知）+ 通知接口（分页/未读数/单条已读/全部已读）+ 前端铃铛角标（30s 轮询）与消息中心页。

**Architecture:** 方案 A（业务方法内同事务创建）——TaskServiceImpl.create 与 ReportServiceImpl.submit/resubmit/approve/reject 在事务内调用 NotificationService 语义化方法落库。前端复用分页模式（Page 信封 + el-pagination + requestSeq 守卫）。TDD：先迁移建表 + 失败测试，再实现。

**Tech Stack:** Spring Boot 3.2.5 / MyBatis-Plus 3.5.7 / MockMvc 集成测试（真实 MySQL）/ Vue 3 + Element Plus / Playwright mock（无端口）

**规格来源:** docs/superpowers/specs/2026-09-08-notification-center-design.md

---

## 全局前置与持久约束（每一步都必须遵守）

- ⚠ **迁移执行（本次特批）**：用户已授权本次执行 `migration-2026-09-08-notification.sql` 一次（Task 1 Step 1）。除此之外**不执行任何**迁移/schema/smoke.sh。
- ⚠ **测试环境**：后端集成测试连真实 MySQL（docker `mysql-dev`）。每个 Run 步骤前确认 `docker info` 可用；不可用则停止并询问用户。
- 不 push（除非用户指示）；不连接真实 MinIO；不启动/停止 8080/5173；不用 npx / 不装依赖。
- `StartupBanner.java`、`backend/.vscode/` 绝不提交；后端与前端分属独立 commit；测试 fixture 自建自清（依赖顺序物理 DELETE）；提交前 `git diff --check`；review-fix 独立 commit、绝不 amend。
- 工作目录 /Users/lijia/workspaces/java/task（bash 不保留 cwd，每条命令都要 `cd`）。

## 文件结构

- Create: `backend/src/main/resources/db/migration-2026-09-08-notification.sql`
- Create: `backend/src/main/java/com/task/entity/Notification.java`、`mapper/NotificationMapper.java`、`vo/NotificationVO.java`、`service/NotificationService.java`、`service/impl/NotificationServiceImpl.java`、`controller/NotificationController.java`
- Modify: `backend/src/main/java/com/task/service/impl/TaskServiceImpl.java`（create 末尾）、`backend/src/main/java/com/task/service/impl/ReportServiceImpl.java`（submit/resubmit/approve/reject 末尾 + 字段）
- Create: `backend/src/test/java/com/task/controller/NotificationControllerTest.java`
- Create: `frontend/src/api/notification.js`、`frontend/src/views/NotificationCenter.vue`
- Modify: `frontend/src/layout/Layout.vue`、`frontend/src/router/index.js`
- Create（/tmp，不入库）: `/tmp/task21_notification_playwright.py`

---

### Task 1: 迁移建表 + 失败测试（RED）

**Files:**
- Create: `backend/src/main/resources/db/migration-2026-09-08-notification.sql`
- Create: `backend/src/test/java/com/task/controller/NotificationControllerTest.java`

- [ ] **Step 1: 写迁移脚本（完整文件）**

```sql
CREATE TABLE IF NOT EXISTS notification (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  type VARCHAR(30) NOT NULL,
  title VARCHAR(100) NOT NULL,
  content VARCHAR(500) NOT NULL,
  task_id BIGINT NULL,
  report_id BIGINT NULL,
  is_read TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;
```

- [ ] **Step 2: 执行迁移（本次特批，仅此一次）**

```bash
cd /Users/lijia/workspaces/java/task && \
docker exec -i mysql-dev mysql -uroot -proot123456 task_assign < backend/src/main/resources/db/migration-2026-09-08-notification.sql && \
docker exec mysql-dev mysql -uroot -proot123456 task_assign -e "SHOW TABLES LIKE 'notification'"
```
Expected: 输出 `notification` 一行。

- [ ] **Step 3: 写失败测试（完整文件）**

> **Review 加固记录**（实现时以上方代码为基础，已按两段 review 强化，以实际提交的测试文件为准）：
> 1. 种子用户 group_id 快照恢复（groupRestore，防污染共享种子数据）
> 2. 全局精确计数改基线相对（unread-count 断言 base+1/base；markAllRead 后置断言为 0 而非基线；跨用户隔离断言 leader1 未读不被误清）
> 3. 列表断言改 records[0].id==nid 与"他人列表不含 nid"
> 4. 清理未用注入（reportMapper/taskMapper/groupMapper/createdUserIds）与 assertTrue(!contains)→assertFalse

`backend/src/test/java/com/task/controller/NotificationControllerTest.java`：

```java
package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.Notification;
import com.task.entity.SysGroup;
import com.task.entity.SysUser;
import com.task.mapper.NotificationMapper;
import com.task.mapper.SysGroupMapper;
import com.task.mapper.SysUserMapper;
import com.task.mapper.TaskMapper;
import com.task.mapper.TaskMemberMapper;
import com.task.mapper.ReportMapper;
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
    @Autowired ReportMapper reportMapper;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired SysUserMapper userMapper;
    @Autowired SysGroupMapper groupMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 自建数据 id（依赖顺序清理：notification → report → task_member → task → sys_group → sys_user） */
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();
    private final List<Long> createdGroupIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

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
        for (Long id : createdUserIds) jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", id);
        createdTaskIds.clear();
        createdMemberIds.clear();
        createdGroupIds.clear();
        createdUserIds.clear();
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
            mvc.perform(post("/api/groups/" + gid + "/members").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\":" + userId(uname) + "}"))
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
        assertTrue(!receivers.contains(userId("leader1")));
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
        org.junit.jupiter.api.Assertions.assertNotNull(n, "提交人应收到通过通知");
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
        org.junit.jupiter.api.Assertions.assertNotNull(n);
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

    /** 未读数 + 单条已读幂等 */
    @Test
    void unreadCountAndMarkReadIdempotent() throws Exception {
        String leaderToken = login("leader1", "123456");
        long taskId = createTask(leaderToken, "通知已读任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        String zToken = login("zhangsan", "123456");
        mvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.count").value(1));
        long nid = notificationsOf(taskId).get(0).getId();
        mvc.perform(put("/api/notifications/" + nid + "/read").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(put("/api/notifications/" + nid + "/read").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0)); // 幂等
        mvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.data.count").value(0));
    }

    /** 全部已读 */
    @Test
    void markAllReadClearsUnread() throws Exception {
        String leaderToken = login("leader1", "123456");
        long taskId = createTask(leaderToken, "通知全读任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        String zToken = login("zhangsan", "123456");
        mvc.perform(put("/api/notifications/read-all").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(get("/api/notifications/unread-count").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.data.count").value(0));
    }

    /** 列表只见自己的 + 分页校验 + 越权 read 他人通知 404 */
    @Test
    void listOwnershipPaginationAndForbiddenRead() throws Exception {
        String leaderToken = login("leader1", "123456");
        long taskId = createTask(leaderToken, "通知越权任务" + System.currentTimeMillis(), "INDIVIDUAL", userId("zhangsan"), null);
        long nid = notificationsOf(taskId).get(0).getId(); // 属于 zhangsan
        // 越权：leader1 读 zhangsan 的通知 → 404
        mvc.perform(put("/api/notifications/" + nid + "/read").header("Authorization", "Bearer " + leaderToken))
                .andExpect(jsonPath("$.code").value(404));
        // zhangsan 列表只含自己的；leader1 列表不含该通知
        String zToken = login("zhangsan", "123456");
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + zToken))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(nid));
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + leaderToken))
                .andExpect(jsonPath("$.data.total").value(0));
        // 分页参数校验
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + zToken).param("page", "0"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/notifications").header("Authorization", "Bearer " + zToken).param("size", "1001"))
                .andExpect(jsonPath("$.code").value(400));
    }
}
```

- [ ] **Step 4: 运行验证失败（RED）**

前置：Docker/mysql-dev 可用（迁移已执行）。

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test -Dtest=NotificationControllerTest`
Expected: FAIL —— 编译失败（Notification/NotificationMapper/NotificationVO 尚不存在）或接口 404。若编译失败即 RED 达成（测试引用未实现的类型）；记录失败形态即可。若因测试文件自身问题（如语法）失败，修测试文件。

---

### Task 2: 后端实现（GREEN）

**Files:**
- Create: `backend/src/main/java/com/task/entity/Notification.java`、`mapper/NotificationMapper.java`、`vo/NotificationVO.java`、`service/NotificationService.java`、`service/impl/NotificationServiceImpl.java`、`controller/NotificationController.java`
- Modify: `backend/src/main/java/com/task/service/impl/TaskServiceImpl.java`、`backend/src/main/java/com/task/service/impl/ReportServiceImpl.java`

- [ ] **Step 1: 实体 + Mapper**

`entity/Notification.java`：
```java
package com.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification")
public class Notification {
    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String content;
    private Long taskId;
    private Long reportId;
    private Integer isRead;
    private LocalDateTime createdAt;
}
```

`mapper/NotificationMapper.java`：
```java
package com.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.task.entity.Notification;

public interface NotificationMapper extends BaseMapper<Notification> {
}
```

- [ ] **Step 2: VO**

`vo/NotificationVO.java`：
```java
package com.task.vo;

import com.task.entity.Notification;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationVO {
    private Long id;
    private String type;
    private String title;
    private String content;
    private Long taskId;
    private Long reportId;
    private boolean read;
    private LocalDateTime createdAt;

    public static NotificationVO from(Notification n) {
        NotificationVO v = new NotificationVO();
        v.setId(n.getId());
        v.setType(n.getType());
        v.setTitle(n.getTitle());
        v.setContent(n.getContent());
        v.setTaskId(n.getTaskId());
        v.setReportId(n.getReportId());
        v.setRead(n.getIsRead() != null && n.getIsRead() == 1);
        v.setCreatedAt(n.getCreatedAt());
        return v;
    }
}
```

- [ ] **Step 3: Service 接口与实现**

`service/NotificationService.java`：
```java
package com.task.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.entity.Report;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.vo.NotificationVO;

import java.util.List;

public interface NotificationService {
    Page<NotificationVO> list(long page, long size);

    long unreadCount();

    void markRead(Long id);

    void markAllRead();

    /** 任务创建成功后：通知除创建者外的全部成员 */
    void notifyTaskAssigned(Task task, SysUser creator, List<SysUser> assignees);

    /** 汇报提交/重提成功后：通知任务创建者（创建者=提交人则跳过） */
    void notifyReportSubmitted(Task task, SysUser submitter);

    /** 审核通过/驳回后：通知汇报提交人（提交人=审核人则跳过） */
    void notifyReportReviewed(Task task, Report report, SysUser reviewer, boolean approved, Integer finalProgress, String reviewComment);
}
```

`service/impl/NotificationServiceImpl.java`：
```java
package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.entity.Notification;
import com.task.entity.Report;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.mapper.NotificationMapper;
import com.task.service.NotificationService;
import com.task.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final NotificationMapper notificationMapper;

    @Override
    public Page<NotificationVO> list(long page, long size) {
        if (page < 1 || size < 1 || size > 1000) throw new BusinessException("分页参数不合法");
        Long uid = UserContext.get().getId();
        Page<Notification> p = notificationMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, uid)
                        .orderByDesc(Notification::getId));
        Page<NotificationVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(p.getRecords().stream().map(NotificationVO::from).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public long unreadCount() {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, UserContext.get().getId())
                .eq(Notification::getIsRead, 0));
    }

    @Override
    public void markRead(Long id) {
        Notification n = notificationMapper.selectById(id);
        // 不存在或非本人一律 404，不泄露他人通知的存在性
        if (n == null || !n.getUserId().equals(UserContext.get().getId())) {
            throw new BusinessException(404, "通知不存在");
        }
        if (n.getIsRead() != 1) {
            n.setIsRead(1);
            notificationMapper.updateById(n);
        }
    }

    @Override
    public void markAllRead() {
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, UserContext.get().getId())
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1));
    }

    @Override
    public void notifyTaskAssigned(Task task, SysUser creator, List<SysUser> assignees) {
        for (SysUser u : assignees) {
            if (u.getId().equals(creator.getId())) continue; // 创建者自己也是成员时不通知本人
            insert(u.getId(), "TASK_ASSIGNED", "新任务分配", "你被分配了任务「" + task.getName() + "」", task.getId(), null);
        }
    }

    @Override
    public void notifyReportSubmitted(Task task, SysUser submitter) {
        if (task.getCreatorId().equals(submitter.getId())) return;
        insert(task.getCreatorId(), "REPORT_SUBMITTED", "待审核汇报",
                "任务「" + task.getName() + "」有新的汇报待你审核", task.getId(), null);
    }

    @Override
    public void notifyReportReviewed(Task task, Report report, SysUser reviewer, boolean approved, Integer finalProgress, String reviewComment) {
        if (reviewer.getId().equals(report.getUserId())) return;
        if (approved) {
            insert(report.getUserId(), "REPORT_APPROVED", "汇报已通过",
                    "你的汇报（任务「" + task.getName() + "」）已通过，最终进度 " + finalProgress + "%",
                    task.getId(), report.getId());
        } else {
            insert(report.getUserId(), "REPORT_REJECTED", "汇报被驳回",
                    "你的汇报（任务「" + task.getName() + "」）被驳回：" + reviewComment,
                    task.getId(), report.getId());
        }
    }

    private void insert(Long userId, String type, String title, String content, Long taskId, Long reportId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setTaskId(taskId);
        n.setReportId(reportId);
        n.setIsRead(0);
        notificationMapper.insert(n);
    }
}
```

- [ ] **Step 4: Controller**

`controller/NotificationController.java`：
```java
package com.task.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.task.common.Result;
import com.task.service.NotificationService;
import com.task.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;

    @GetMapping
    public Result<Page<NotificationVO>> list(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long size) {
        return Result.ok(notificationService.list(page, size));
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        return Result.ok(Map.of("count", notificationService.unreadCount()));
    }

    @PutMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return Result.ok();
    }

    @PutMapping("/read-all")
    public Result<Void> markAllRead() {
        notificationService.markAllRead();
        return Result.ok();
    }
}
```

- [ ] **Step 5: 插入点 1 —— TaskServiceImpl.create**

字段区（`private final TaskAttachmentMapper attachmentMapper;` 之后）加：
```java
    private final NotificationService notificationService;
```
imports 加 `import com.task.service.NotificationService;`。

create 方法末尾，`return task.getId();` 之前插入：
```java
        // 站内信：被分配任务（同事务；创建者本人跳过）
        notificationService.notifyTaskAssigned(task, creator, assignees);
```

- [ ] **Step 6: 插入点 2-4 —— ReportServiceImpl**

字段区（`private final ReportHistoryMapper historyMapper;` 之后）加：
```java
    private final NotificationService notificationService;
```
imports 加 `import com.task.service.NotificationService;`。

① submit：`recordHistory(r, "SUBMITTED", null);` 之后、`return r.getId();` 之前插入：
```java
        // 站内信：有汇报待审核（创建者=提交人时跳过）
        notificationService.notifyReportSubmitted(task, current);
```

② approve：方法末尾 `taskMapper.updateById(task);` 之后插入：
```java
        // 站内信：汇报已通过（提交人=审核人时跳过）
        notificationService.notifyReportReviewed(task, report, reviewer, true, finalProgress, req.getReviewComment());
```

③ reject：`recordHistory(report, "REJECTED", null);` 之后插入：
```java
        // 站内信：汇报被驳回（提交人=审核人时跳过）
        notificationService.notifyReportReviewed(task, report, reviewer, false, null, req.getReviewComment());
```

④ resubmit：`recordHistory(report, "RESUBMITTED", null);` 之后插入：
```java
        // 站内信：重提后再次通知审核人
        notificationService.notifyReportSubmitted(task, UserContext.get());
```

- [ ] **Step 7: 运行 NotificationControllerTest（GREEN）**

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test -Dtest=NotificationControllerTest`
Expected: PASS —— Tests run: 9, Failures: 0, Errors: 0。

- [ ] **Step 8: 后端全量回归**

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test`
Expected: 全绿（102 + 9 = 111，以实际输出为准）。

- [ ] **Step 9: Commit（backend 全部 9 个文件）**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && \
git add backend/src/main/resources/db/migration-2026-09-08-notification.sql \
        backend/src/main/java/com/task/entity/Notification.java \
        backend/src/main/java/com/task/mapper/NotificationMapper.java \
        backend/src/main/java/com/task/vo/NotificationVO.java \
        backend/src/main/java/com/task/service/NotificationService.java \
        backend/src/main/java/com/task/service/impl/NotificationServiceImpl.java \
        backend/src/main/java/com/task/controller/NotificationController.java \
        backend/src/main/java/com/task/service/impl/TaskServiceImpl.java \
        backend/src/main/java/com/task/service/impl/ReportServiceImpl.java \
        backend/src/test/java/com/task/controller/NotificationControllerTest.java && \
git status --short && \
git commit -m "feat: in-app notifications for task and report events"
```
Expected: 工作区只剩 `?? backend/src/main/java/com/task/config/StartupBanner.java`。

---

### Task 3: 前端铃铛与消息中心

**Files:**
- Create: `frontend/src/api/notification.js`、`frontend/src/views/NotificationCenter.vue`
- Modify: `frontend/src/layout/Layout.vue`、`frontend/src/router/index.js`

- [ ] **Step 1: API 模块（完整文件）**

`frontend/src/api/notification.js`：
```js
import request from './request'

export const listNotifications = (params) => request.get('/notifications', { params })
export const unreadCount = () => request.get('/notifications/unread-count')
export const markRead = (id) => request.put(`/notifications/${id}/read`)
export const markAllRead = () => request.put('/notifications/read-all')
```

- [ ] **Step 2: 路由**

`frontend/src/router/index.js` children 数组（`users` 之后）加：
```js
      { path: 'notifications', name: 'notification-center', component: () => import('../views/NotificationCenter.vue') }
```
（meta 由 Layout 的 pageTitle 映射提供，不需要在路由里加 meta。）

- [ ] **Step 3: 消息中心页（完整文件）**

`frontend/src/views/NotificationCenter.vue`：
```vue
<template>
  <div class="section">
    <div class="page-head">
      <h2>消息中心</h2>
      <el-button type="primary" plain :icon="Check" :disabled="unread === 0 || markingAll"
                 :loading="markingAll" @click="markAll">全部已读</el-button>
    </div>

    <div v-if="error" class="section center-box">
      <el-result icon="error" title="加载失败" :sub-title="error">
        <template #extra>
          <el-button type="primary" :icon="Refresh" @click="load">重试</el-button>
        </template>
      </el-result>
    </div>
    <div v-else-if="loading" class="section center-box">
      <el-skeleton :rows="5" animated />
    </div>
    <div v-else-if="!records.length" class="section center-box">
      <el-empty description="暂无通知" :image-size="80" />
    </div>
    <div v-else class="notif-list">
      <article v-for="n in records" :key="n.id" class="notif-row" :class="{ 'notif-unread': !n.read }"
               :data-notification-id="n.id" role="link" tabindex="0"
               @click="openNotification(n)" @keydown.enter="openNotification(n)">
        <span v-if="!n.read" class="notif-dot" aria-hidden="true"></span>
        <div class="notif-main">
          <div class="notif-head">
            <strong>{{ n.title }}</strong>
            <span class="notif-time">{{ formatTime(n.createdAt) }}</span>
          </div>
          <p class="notif-content">{{ n.content }}</p>
        </div>
        <el-icon class="notif-arrow"><ArrowRight /></el-icon>
      </article>
      <el-pagination v-if="total > pageSize" class="notif-pagination" layout="prev, pager, next, total"
                     v-model:current-page="page" :page-size="pageSize" :total="total"
                     @current-change="load" />
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Check, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listNotifications, markAllRead, markRead, unreadCount } from '../api/notification'

const router = useRouter()
const records = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const loading = ref(false)
const error = ref('')
const unread = ref(0)
const markingAll = ref(false)

/** 请求序号：只有最新一次请求的响应才能更新页面状态 */
let requestSeq = 0

const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

const fetchUnread = async () => {
  try {
    unread.value = (await unreadCount()).count
  } catch (e) {
    // 角标失败静默，不打扰主流程
  }
}

const load = async () => {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const data = await listNotifications({ page: page.value, size: pageSize })
    if (seq !== requestSeq) return // 过期响应丢弃
    records.value = Array.isArray(data.records) ? data.records : []
    total.value = data.total
  } catch (e) {
    if (seq !== requestSeq) return
    error.value = e.message || '网络错误'
    records.value = []
    total.value = 0
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

const openNotification = async (n) => {
  if (!n.read) {
    try {
      await markRead(n.id)
      n.read = true
      unread.value = Math.max(0, unread.value - 1)
    } catch (e) {
      ElMessage.error(e.message || '操作失败')
      return
    }
  }
  router.push(n.taskId ? `/tasks/${n.taskId}` : '/reports/pending')
}

const markAll = async () => {
  if (markingAll.value || unread.value === 0) return
  markingAll.value = true
  try {
    await markAllRead()
    records.value.forEach((n) => { n.read = true })
    unread.value = 0
    ElMessage.success('已全部标记为已读')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    markingAll.value = false
  }
}

onMounted(() => {
  fetchUnread()
  load()
})
</script>

<style scoped>
.notif-list { display: flex; flex-direction: column; gap: 8px; }
.notif-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  cursor: pointer;
}
.notif-row:hover { border-color: var(--color-primary); }
.notif-unread { background: var(--color-primary-soft, #eef4ff); }
.notif-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--color-danger); flex-shrink: 0;
}
.notif-main { flex: 1; min-width: 0; }
.notif-head { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
.notif-time { color: var(--color-text-muted); font-size: 12px; flex-shrink: 0; }
.notif-content { margin: 4px 0 0; color: var(--color-text-secondary); font-size: 13px; }
.notif-arrow { color: var(--color-text-muted); }
.notif-pagination { display: flex; justify-content: center; margin-top: 12px; }
</style>
```

- [ ] **Step 4: Layout 铃铛**

模板：`.topbar-right` 内、`<el-dropdown>` 之前插入：
```html
          <el-popover placement="bottom-end" :width="320" trigger="click" @show="loadRecent">
            <template #reference>
              <el-badge :value="unread" :hidden="unread === 0" class="bell-badge">
                <el-button text :icon="Bell" class="bell-btn" aria-label="通知" title="通知" />
              </el-badge>
            </template>
            <div class="bell-panel">
              <div class="bell-head">
                <strong>通知</strong>
                <el-button link type="primary" size="small" :disabled="unread === 0" @click="markAll">全部已读</el-button>
              </div>
              <div v-if="!recent.length" class="bell-empty">暂无通知</div>
              <div v-for="n in recent" :key="n.id" class="bell-item" :class="{ 'bell-unread': !n.read }"
                   @click="openNotification(n)">
                <div class="bell-item-head">
                  <strong>{{ n.title }}</strong>
                  <span class="bell-time">{{ formatTime(n.createdAt) }}</span>
                </div>
                <div class="bell-content">{{ n.content }}</div>
              </div>
              <div class="bell-foot">
                <el-button link type="primary" @click="goCenter">查看全部</el-button>
              </div>
            </div>
          </el-popover>
```

script：imports 改为（在既有 imports 后加）：
```js
import { ElMessage } from 'element-plus'
import { Bell } from '@element-plus/icons-vue'
import { listNotifications, markAllRead, markRead, unreadCount } from '../api/notification'
```
状态与方法（`handleCommand` 之前）：
```js
const unread = ref(0)
const recent = ref([])
let pollTimer = null

const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

/** 拉未读数（角标）；失败静默，不打扰主流程 */
const fetchUnread = async () => {
  try {
    unread.value = (await unreadCount()).count
  } catch (e) { /* 静默 */ }
}

const loadRecent = async () => {
  try {
    recent.value = (await listNotifications({ page: 1, size: 5 })).records || []
    await fetchUnread()
  } catch (e) { /* 静默 */ }
}

const markAll = async () => {
  try {
    await markAllRead()
    unread.value = 0
    recent.value.forEach((n) => { n.read = true })
    ElMessage.success('已全部标记为已读')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

const openNotification = async (n) => {
  if (!n.read) {
    try {
      await markRead(n.id)
      n.read = true
      unread.value = Math.max(0, unread.value - 1)
    } catch (e) {
      ElMessage.error(e.message || '操作失败')
      return
    }
  }
  router.push(n.taskId ? `/tasks/${n.taskId}` : '/reports/pending')
}

const goCenter = () => router.push('/notifications')
```
onMounted/onBeforeUnmount 追加轮询（在 resize 监听之后）：
```js
onMounted(() => {
  fetchUnread()
  pollTimer = setInterval(fetchUnread, 30000)
})
onBeforeUnmount(() => { if (pollTimer) clearInterval(pollTimer) })
```
pageTitle map 加一项：`'/notifications': '消息中心',`。

样式（`<style scoped>` 末尾）：
```css
.bell-badge { margin-right: 4px; }
.bell-btn { font-size: 18px; }
.bell-panel { display: flex; flex-direction: column; }
.bell-head { display: flex; justify-content: space-between; align-items: center; padding-bottom: 8px; border-bottom: 1px solid var(--color-border); }
.bell-empty { padding: 24px 0; text-align: center; color: var(--color-text-muted); font-size: 13px; }
.bell-item { padding: 8px 4px; border-bottom: 1px solid var(--color-border); cursor: pointer; }
.bell-item:last-of-type { border-bottom: none; }
.bell-item:hover .bell-item-head strong { color: var(--color-primary); }
.bell-unread .bell-item-head strong::before { content: ''; display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--color-danger); margin-right: 6px; vertical-align: middle; }
.bell-item-head { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
.bell-time { color: var(--color-text-muted); font-size: 12px; flex-shrink: 0; }
.bell-content { margin-top: 2px; color: var(--color-text-secondary); font-size: 12px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.bell-foot { padding-top: 8px; text-align: center; }
```

- [ ] **Step 5: 构建**

Run: `cd /Users/lijia/workspaces/java/task/frontend && npm run build`
Expected: 构建成功。

- [ ] **Step 6: Commit（前端 4 文件）**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && \
git add frontend/src/api/notification.js \
        frontend/src/views/NotificationCenter.vue \
        frontend/src/layout/Layout.vue \
        frontend/src/router/index.js && \
git commit -m "feat: notification bell and center page"
```

---

### Task 4: 无端口 mock 走查

**Files:**
- Create（/tmp，不入库）: `/tmp/task21_notification_playwright.py`

- [ ] **Step 1: 写 mock 脚本（完整文件）**

> **执行修正记录**（mock 保真缺陷，与前端无关；以实际 /tmp/task21_notification_playwright.py 为准）：
> 1. 未读 ID 改为 513-515（最新三条）——铃铛面板拉的是最新 5 条，原 501-503 为最旧，面板内不会出现未读
> 2. 516 新通知的注入从 read-all 响应改到 unread-count 分支（read-all 后的第 2 次 unread-count GET 时注入，第 1 次是中心页挂载、第 2 次是 W5 刷新）——原方案让 W3 总数变 16 且 W4 提前消费掉新通知，W5 必挂

```python
#!/usr/bin/env python3
"""Task 21 站内信 mock 走查（无端口）。

验证点：
  W1 登录后铃铛角标 = 3（未读数）
  W2 点铃铛 → 面板最近 5 条、未读高亮；全部已读 → 角标隐藏
  W3 消息中心页：15 条分页（第 1 页 10 条 + 共 15 条），已读行无未读点
  W4 点击带 taskId 的通知 → 跳转任务详情
  W5 刷新后（mock 在 read-all 后注入 1 条新未读）→ 角标 = 1；点开该条 → 已读并跳转
"""
import json
import re
import sys
from pathlib import Path
from urllib.parse import parse_qsl

from playwright.sync_api import sync_playwright

DIST = Path("/Users/lijia/workspaces/java/task/frontend/dist")
BASE = "http://app.test"
assert DIST.is_dir(), "先执行 npm run build"

# id 501..515（15 条，501-503 未读）；516 为 read-all 后注入的新未读
NOTIFS = [dict(id=i, type="REPORT_SUBMITTED", title="待审核汇报",
               content=f"任务「验收通知任务{i - 500}」有新的汇报待你审核",
               taskId=12, reportId=None, read=(i > 503), createdAt="2026-09-08T10:00:00")
          for i in range(501, 516)]


def handler(route):
    url = route.request.url
    if url.startswith(BASE + "/api/"):
        path = url[len(BASE):]
        qs = ""
        if "?" in path:
            path, qs = path.split("?", 1)
        if path == "/api/auth/login" and route.request.method == "POST":
            return route.fulfill(status=200, content_type="application/json", body=json.dumps({
                "code": 0, "data": {"token": "t2", "user": {"id": 2, "username": "leader1",
                    "realName": "李组长", "role": "LEADER", "groupId": None, "groupName": None}}}))
        if path == "/api/notifications" and route.request.method == "GET":
            params = dict(parse_qsl(qs, keep_blank_values=True))
            page, size = int(params.get("page", "1")), int(params.get("size", "10"))
            ts = sorted(NOTIFS, key=lambda n: -n["id"])
            start = (page - 1) * size
            return route.fulfill(status=200, content_type="application/json", body=json.dumps({
                "code": 0, "data": {"records": ts[start:start + size],
                                    "total": len(ts), "current": page, "size": size}}))
        if path == "/api/notifications/unread-count" and route.request.method == "GET":
            return route.fulfill(status=200, content_type="application/json", body=json.dumps({
                "code": 0, "data": {"count": sum(1 for n in NOTIFS if not n["read"])}}))
        if path == "/api/notifications/read-all" and route.request.method == "PUT":
            for n in NOTIFS:
                n["read"] = True
            # read-all 后模拟一条新到达的未读通知（供 W5 验证轮询/挂载刷新角标）
            NOTIFS.append(dict(id=516, type="REPORT_APPROVED", title="汇报已通过",
                               content="你的汇报（任务「验收通知任务16」）已通过，最终进度 80%",
                               taskId=12, reportId=None, read=False, createdAt="2026-09-08T11:00:00"))
            return route.fulfill(status=200, content_type="application/json",
                                 body=json.dumps({"code": 0, "data": None}))
        mm = re.match(r"^/api/notifications/(\d+)/read$", path)
        if mm and route.request.method == "PUT":
            for n in NOTIFS:
                if n["id"] == int(mm.group(1)):
                    n["read"] = True
            return route.fulfill(status=200, content_type="application/json",
                                 body=json.dumps({"code": 0, "data": None}))
        if path == "/api/tasks/12" and route.request.method == "GET":
            return route.fulfill(status=200, content_type="application/json", body=json.dumps({
                "code": 0, "data": {"id": 12, "name": "验收通知任务", "description": None,
                                    "creatorId": 2, "creatorName": "李组长", "assignType": "INDIVIDUAL",
                                    "assigneeId": 3, "assigneeName": "张三", "status": "DOING",
                                    "deadline": None, "progress": 30, "doneAt": None,
                                    "createdAt": "2026-09-08T09:00:00", "members": [], "attachments": []}}))
        if path.startswith("/api/tasks/12/") and route.request.method == "GET":
            return route.fulfill(status=200, content_type="application/json",
                                 body=json.dumps({"code": 0, "data": []}))
        return route.fulfill(status=200, content_type="application/json",
                             body=json.dumps({"code": 0, "data": None}))
    p = url[len(BASE):].split("?", 1)[0]
    f = DIST / (p.lstrip("/") or "index.html")
    if not f.exists() or f.is_dir():
        f = DIST / "index.html"
    if f.is_dir():
        f = f / "index.html"
    mime = {".js": "text/javascript", ".css": "text/css", ".html": "text/html",
            ".svg": "image/svg+xml", ".png": "image/png"}
    route.fulfill(status=200, content_type=mime.get(f.suffix.lower(), "application/octet-stream"),
                  body=f.read_bytes())


results = []


def record(w, ok, note):
    results.append(ok)
    print(f"{'✓' if ok else '✗'} {w}: {note}")


def badge_text(page):
    el = page.locator(".bell-badge .el-badge__content")
    return el.inner_text() if el.count() else ""


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1280, "height": 900})
        page.route("**/*", handler)
        page.set_default_timeout(8000)

        page.goto(BASE + "/login")
        page.get_by_placeholder("请输入用户名").fill("leader1")
        page.get_by_placeholder("请输入密码").fill("123456")
        page.locator(".login-btn").click()
        page.wait_for_url("**/tasks")
        page.wait_for_timeout(800)

        # W1 角标 = 3
        ok_w1 = badge_text(page) == "3"
        record("W1", ok_w1, f"铃铛角标未读数=3（实际={badge_text(page)}）")

        # W2 面板最近 5 条 + 未读高亮 + 全部已读
        page.locator(".bell-btn").click()
        page.wait_for_timeout(500)
        items = page.locator(".bell-item")
        ok_w2a = items.count() == 5 and page.locator(".bell-item.bell-unread").count() == 3
        page.locator(".bell-head button", has_text="全部已读").click()
        page.wait_for_timeout(500)
        ok_w2b = badge_text(page) == ""
        record("W2", ok_w2a and ok_w2b,
               f"面板5条/未读3条高亮/全部已读后角标隐藏（a={ok_w2a} b={ok_w2b}）")

        # W3 消息中心分页
        page.locator(".bell-foot button", has_text="查看全部").click()
        page.wait_for_url("**/notifications")
        page.wait_for_timeout(600)
        ok_w3a = page.locator(".notif-row").count() == 10 \
            and "共 15 条" in page.locator(".el-pagination__total").inner_text()
        page.locator(".el-pager li", has_text="2").first.click()
        page.wait_for_timeout(500)
        ok_w3b = page.locator(".notif-row").count() == 5
        record("W3", ok_w3a and ok_w3b, f"中心页第1页10条/共15条/第2页5条（a={ok_w3a} b={ok_w3b}）")

        # W4 点击带 taskId 通知 → 跳转任务详情
        page.locator(".el-pager li", has_text="1").first.click()
        page.wait_for_timeout(400)
        page.locator(".notif-row").first.click()
        page.wait_for_url("**/tasks/12")
        ok_w4 = page.url.endswith("/tasks/12") and page.locator(".section").count() >= 1
        record("W4", ok_w4, "点击通知跳转任务详情页")

        # W5 刷新 → 新通知角标=1 → 点击 → 已读并跳转
        page.reload()
        page.wait_for_timeout(800)
        ok_w5a = badge_text(page) == "1"
        page.locator(".bell-btn").click()
        page.wait_for_timeout(400)
        page.locator(".bell-item.bell-unread").first.click()
        page.wait_for_url("**/tasks/12")
        page.wait_for_timeout(400)
        page.reload()
        page.wait_for_timeout(800)
        ok_w5b = badge_text(page) == ""
        record("W5", ok_w5a and ok_w5b, f"刷新后新通知角标=1，点击已读后归零（a={ok_w5a} b={ok_w5b}）")

        browser.close()

    failed = [r for r in results if not r]
    print(f"\n==== 站内信 mock 走查：{len(results) - len(failed)}/{len(results)} 通过 ====")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 运行走查**

Run: `cd /tmp && python3 task21_notification_playwright.py`
Expected: W1-W5 全 ✓，3/… 5/5 通过。若失败：先判断 mock 保真问题 vs 真实前端缺陷，不得弱化断言，查清后如实报告。

- [ ] **Step 3: 复跑既有 mock（回归）**

```bash
cd /tmp && python3 task19_pagination_playwright.py && python3 task20_mytasks_playwright.py
```
Expected: 各 3/3（若文件已被 /tmp 清理则报告并跳过，不重建）。

---

### Task 5: 收尾与报告

- [ ] **Step 1: 全量回归**

- 后端：`cd /Users/lijia/workspaces/java/task/backend && mvn test` → 111 全绿（Docker 前置）
- 前端：`cd /Users/lijia/workspaces/java/task/frontend && npm run build` → 成功
- mock：task21 5/5、task19/task20 各 3/3

- [ ] **Step 2: 工作区检查与报告**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && git log --oneline -6 && git status --short
```
Expected: 工作区仅 `?? backend/src/main/java/com/task/config/StartupBanner.java`（绝不提交）。

报告：commit SHA 列表、已提交文件、测试结果（NotificationControllerTest 9/9、全量、build、mock）、迁移执行记录（已执行一次）、残留风险、**不 push**（除非用户指示）。

---

## 遗留事项清单（终审记录，2026-09-08）

有意识接受/延后的项，后续会话接手时直接查此处：

1. **sibling 测试类不清通知残留**：TaskControllerTest 等测试类通过 API 造任务产生通知，其 cleanup 不删 notification 行；dev 库每次全量跑增长约 270 行。后续任务：给 sibling cleanup 加 `DELETE FROM notification WHERE task_id IN (...)` 或抽公共测试基类。
2. **轮询失败 toast 噪音**：后端不可达时 30s 轮询每轮经拦截器弹一次「网络错误」。已接受；彻底修复需给 request.js 加按请求静默标记。
3. **中心页分页 total 显示**：NotificationCenter 显示「共 N 条」，TaskList/UserManage 隐藏——风格差异，已接受。
4. **dev 库通知残留增长**：与 #1 同源，测试库数据，已接受。
5. **自我通知跳过分支无回归测试**：spec 列的「创建者自己提交 → 不产生」「提交人=审核人 → 不产生」两条 skip 分支无对应测试（生产可达但仅由 NotificationServiceImpl 守卫）。后续补 2 个测试。
6. **notification 表无 user_id 索引**：所有查询按 user_id 过滤，表随使用增长。后续加 `KEY idx_notification_user (user_id)`（需新迁移）。
7. **任务删除后通知残留**：通知不可删（设计），删除任务不清理其通知；点击该类通知落到任务详情错误态（优雅降级）。已接受。
8. **双源未读收敛方向**：铃铛角标 ≤30s 收敛；中心页视图状态在重新进入时收敛（原地操作自愈）。语义精确表述，无缺陷。
