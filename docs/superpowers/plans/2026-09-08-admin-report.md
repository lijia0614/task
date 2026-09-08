# 管理员报表（总览卡片）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 管理员总览报表：`GET /api/admin/reports/summary?range=all|7d|30d` 聚合任务总数/进行中/已完成/已过期/完成率/逾期率（仅 ADMIN），前端「数据报表」页展示 6 张卡片（4 数字卡 + 2 比例条卡）并可切换时间范围。

**Architecture:** 单接口 SQL 聚合（MyBatis-Plus 三次 selectCount，显式 `eq(deleted, 0)`——项目无 @TableLogic）；四分类互斥口径与列表 OVERDUE 一致；纯 CSS 比例条（不引图表库）。TDD：先失败测试，再实现。

**Tech Stack:** Spring Boot 3.2.5 / MyBatis-Plus 3.5.7 / MockMvc 集成测试（真实 MySQL）/ Vue 3 + Element Plus / Playwright mock（无端口）

**规格来源:** docs/superpowers/specs/2026-09-08-admin-report-design.md

---

## 全局前置与持久约束（每一步都必须遵守）

- ⚠ **测试环境**：后端集成测试连真实 MySQL（docker `mysql-dev`）。每个 Run 步骤前确认 `docker info` 可用；不可用则停止并询问用户。
- 不 push（除非用户指示）；本特性无迁移（纯查询聚合）；不连接真实 MinIO；不启动/停止 8080/5173；不用 npx / 不装依赖。
- `StartupBanner.java` 现为 **tracked**（仓库主人提交），**不要修改它**；后端与前端分属独立 commit；测试 fixture 自建自清；提交前 `git diff --check`；review-fix 独立 commit、绝不 amend。
- commit 说明用中文（用户偏好）。
- 工作目录 /Users/lijia/workspaces/java/task（bash 不保留 cwd，每条命令都要 `cd`）。

## 文件结构

- Create: `backend/src/main/java/com/task/vo/ReportSummaryVO.java`、`backend/src/main/java/com/task/service/AdminReportService.java`、`backend/src/main/java/com/task/service/impl/AdminReportServiceImpl.java`、`backend/src/main/java/com/task/controller/AdminReportController.java`
- Create: `backend/src/test/java/com/task/controller/AdminReportControllerTest.java`
- Create: `frontend/src/views/ReportOverview.vue`
- Modify: `frontend/src/router/index.js`、`frontend/src/layout/Layout.vue`
- Create（/tmp，不入库）: `/tmp/task22_adminreport_playwright.py`

---

### Task 1: 后端失败测试（RED）

**Files:**
- Create: `backend/src/test/java/com/task/controller/AdminReportControllerTest.java`

- [ ] **Step 1: 写失败测试（完整文件）**

```java
package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.enums.AssignType;
import com.task.enums.TaskStatus;
import com.task.mapper.SysUserMapper;
import com.task.mapper.TaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理员报表契约（Task 22）：四分类互斥/时间范围/deleted 排除/权限/空态/非法参数 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class AdminReportControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired TaskMapper taskMapper;
    @Autowired SysUserMapper userMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 自建任务 id（物理清理；直插无 task_member 行） */
    private final List<Long> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        createdTaskIds.clear();
    }

    private String login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    /** 直插任务（不设 created_at 时走 DB 默认=当前时间；可指定 status/deadline/createdAt；登记清理 id） */
    private long insertTask(TaskStatus status, LocalDateTime deadline, LocalDateTime createdAt) {
        SysUser leader1 = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "leader1"));
        SysUser zhangsan = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "zhangsan"));
        Task t = new Task();
        t.setName("报表测试任务" + System.currentTimeMillis() + "-" + createdTaskIds.size());
        t.setCreatorId(leader1.getId());
        t.setAssignType(AssignType.INDIVIDUAL);
        t.setAssigneeId(zhangsan.getId());
        t.setStatus(status);
        t.setDeadline(deadline);
        t.setProgress(0);
        if (createdAt != null) t.setCreatedAt(createdAt);
        taskMapper.insert(t);
        createdTaskIds.add(t.getId());
        return t.getId();
    }

    /** 四分类互斥 + deleted 排除：5 条有效（doing3/done1/overdue1）+ 1 条已删除 */
    @Test
    void summaryClassifiesFourExclusiveGroups() throws Exception {
        String token = login("admin", "admin123");
        insertTask(TaskStatus.DOING, LocalDateTime.now().plusDays(5), null);   // 进行中
        insertTask(TaskStatus.DOING, LocalDateTime.now().plusDays(5), null);   // 进行中
        insertTask(TaskStatus.DOING, LocalDateTime.now().minusDays(5), null);  // 已过期
        insertTask(TaskStatus.DONE, LocalDateTime.now().minusDays(5), null);   // 已完成
        insertTask(TaskStatus.DOING, null, null);                              // 无 deadline → 进行中
        long deletedId = insertTask(TaskStatus.DOING, LocalDateTime.now().minusDays(5), null);
        // 项目把 deleted 配置为全局逻辑删除字段（application.yml logic-delete-field），
        // updateById 写不进该列，必须走原生 SQL（参照 DeletedTaskReportCommentsTest）
        jdbcTemplate.update("UPDATE task SET deleted = 1 WHERE id = ?", deletedId);
        // 自证前提：确认 deleted 真的落库
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT deleted FROM task WHERE id = ?", Integer.class, deletedId));

        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.doing").value(3))
                .andExpect(jsonPath("$.data.done").value(1))
                .andExpect(jsonPath("$.data.overdue").value(1))
                .andExpect(jsonPath("$.data.completionRate").value(20))
                .andExpect(jsonPath("$.data.overdueRate").value(20));
    }

    /** 时间范围按 created_at 过滤（cohort 口径） */
    @Test
    void rangeFiltersByCreatedAt() throws Exception {
        String token = login("admin", "admin123");
        insertTask(TaskStatus.DOING, null, LocalDateTime.now().minusDays(10));
        insertTask(TaskStatus.DOING, null, LocalDateTime.now().minusDays(3));

        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token).param("range", "7d"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));   // 只有 3 天前那条
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token).param("range", "30d"))
                .andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.total").value(2));   // 默认 all
    }

    /** 仅管理员：EMPLOYEE/LEADER 一律 403 */
    @Test
    void employeeAndLeaderForbidden() throws Exception {
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + login("zhangsan", "123456")))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + login("leader1", "123456")))
                .andExpect(jsonPath("$.code").value(403));
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + login("admin", "admin123")))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 范围内无任务 → 全 0、两率 0 */
    @Test
    void emptyRangeReturnsAllZeros() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token).param("range", "7d"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.doing").value(0))
                .andExpect(jsonPath("$.data.done").value(0))
                .andExpect(jsonPath("$.data.overdue").value(0))
                .andExpect(jsonPath("$.data.completionRate").value(0))
                .andExpect(jsonPath("$.data.overdueRate").value(0));
    }

    /** range 非法值 → 400 */
    @Test
    void invalidRangeReturn400() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/admin/reports/summary").header("Authorization", "Bearer " + token).param("range", "foo"))
                .andExpect(jsonPath("$.code").value(400));
    }
}
```

- [ ] **Step 2: 运行验证失败（RED）**

前置：`docker info` 可用且 mysql-dev 在跑。

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test -Dtest=AdminReportControllerTest`
Expected: FAIL —— 编译失败（AdminReportController 等类不存在）或路由 404。若测试文件自身语法问题则修测试文件。

---

### Task 2: 后端实现（GREEN）

**Files:**
- Create: `backend/src/main/java/com/task/vo/ReportSummaryVO.java`、`backend/src/main/java/com/task/service/AdminReportService.java`、`backend/src/main/java/com/task/service/impl/AdminReportServiceImpl.java`、`backend/src/main/java/com/task/controller/AdminReportController.java`

- [ ] **Step 1: VO（完整文件）**

`vo/ReportSummaryVO.java`：
```java
package com.task.vo;

import lombok.Data;

@Data
public class ReportSummaryVO {
    private long total;
    private long doing;
    private long done;
    private long overdue;
    private int completionRate;
    private int overdueRate;
}
```

- [ ] **Step 2: Service 接口（完整文件）**

`service/AdminReportService.java`：
```java
package com.task.service;

import com.task.vo.ReportSummaryVO;

public interface AdminReportService {
    /** 管理员总览统计（四分类互斥；时间范围按任务创建时间过滤） */
    ReportSummaryVO reportSummary(String range);
}
```

- [ ] **Step 3: Service 实现（完整文件）**

`service/impl/AdminReportServiceImpl.java`：
```java
package com.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.task.auth.UserContext;
import com.task.common.BusinessException;
import com.task.entity.Task;
import com.task.enums.Role;
import com.task.enums.TaskStatus;
import com.task.mapper.TaskMapper;
import com.task.service.AdminReportService;
import com.task.vo.ReportSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminReportServiceImpl implements AdminReportService {
    private final TaskMapper taskMapper;

    @Override
    public ReportSummaryVO reportSummary(String range) {
        if (UserContext.get().getRole() != Role.ADMIN) throw new BusinessException(403, "无权操作");
        LocalDateTime from;
        if (range == null || "all".equals(range)) {
            from = null;
        } else if ("7d".equals(range)) {
            from = LocalDateTime.now().minusDays(7);
        } else if ("30d".equals(range)) {
            from = LocalDateTime.now().minusDays(30);
        } else {
            throw new BusinessException("时间范围不合法");
        }
        // 项目无 @TableLogic，逻辑删除必须显式排除（deleted 默认 0，等值条件安全）
        LocalDateTime now = LocalDateTime.now();
        long total = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .ge(from != null, Task::getCreatedAt, from)
                .eq(Task::getDeleted, 0));
        long done = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .ge(from != null, Task::getCreatedAt, from)
                .eq(Task::getDeleted, 0)
                .eq(Task::getStatus, TaskStatus.DONE));
        long overdue = taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                .ge(from != null, Task::getCreatedAt, from)
                .eq(Task::getDeleted, 0)
                .ne(Task::getStatus, TaskStatus.DONE)
                .lt(Task::getDeadline, now));
        long doing = total - done - overdue;

        ReportSummaryVO vo = new ReportSummaryVO();
        vo.setTotal(total);
        vo.setDoing(doing);
        vo.setDone(done);
        vo.setOverdue(overdue);
        vo.setCompletionRate(total == 0 ? 0 : (int) (done * 100 / total));
        vo.setOverdueRate(total == 0 ? 0 : (int) (overdue * 100 / total));
        return vo;
    }
}
```

- [ ] **Step 4: Controller（完整文件）**

`controller/AdminReportController.java`：
```java
package com.task.controller;

import com.task.common.Result;
import com.task.service.AdminReportService;
import com.task.vo.ReportSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {
    private final AdminReportService adminReportService;

    @GetMapping("/summary")
    public Result<ReportSummaryVO> summary(@RequestParam(defaultValue = "all") String range) {
        return Result.ok(adminReportService.reportSummary(range));
    }
}
```

- [ ] **Step 5: 运行 AdminReportControllerTest（GREEN）**

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test -Dtest=AdminReportControllerTest`
Expected: PASS —— Tests run: 5, Failures: 0, Errors: 0。

- [ ] **Step 6: 后端全量回归**

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test`
Expected: 全绿（111 + 5 = 116，以实际输出为准）。

- [ ] **Step 7: Commit（仅 backend 5 文件）**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && \
git add backend/src/main/java/com/task/vo/ReportSummaryVO.java \
        backend/src/main/java/com/task/service/AdminReportService.java \
        backend/src/main/java/com/task/service/impl/AdminReportServiceImpl.java \
        backend/src/main/java/com/task/controller/AdminReportController.java \
        backend/src/test/java/com/task/controller/AdminReportControllerTest.java && \
git status --short && \
git commit -m "feat: 管理员报表总览聚合接口"
```
Expected: 工作区 clean（StartupBanner 为 tracked 且未修改）。

---

### Task 3: 前端报表页

**Files:**
- Create: `frontend/src/views/ReportOverview.vue`
- Modify: `frontend/src/router/index.js`、`frontend/src/layout/Layout.vue`

- [ ] **Step 1: 新页面（完整文件）**

`frontend/src/views/ReportOverview.vue`：
```vue
<template>
  <div class="section">
    <div class="page-head">
      <h2>数据报表</h2>
      <el-radio-group v-model="range" class="range-switch" @change="load">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="7d">近 7 天</el-radio-button>
        <el-radio-button value="30d">近 30 天</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="error" class="section center-box">
      <el-result icon="error" title="加载失败" :sub-title="error">
        <template #extra>
          <el-button type="primary" :icon="Refresh" @click="load">重试</el-button>
        </template>
      </el-result>
    </div>
    <div v-else-if="loading" class="section center-box">
      <el-skeleton :rows="3" animated />
    </div>
    <div v-else class="stat-grid">
      <div v-for="c in cards" :key="c.label" class="stat-card">
        <div class="stat-label">{{ c.label }}</div>
        <div class="stat-value">{{ c.value }}</div>
        <div v-if="c.rate !== undefined" class="rate-bar">
          <div class="rate-fill" :style="{ width: c.rate + '%' }"></div>
        </div>
        <div v-if="c.rate !== undefined" class="stat-rate">{{ c.rate }}%</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import request from '../api/request'

const range = ref('all')
const data = ref(null)
const error = ref('')
const loading = ref(false)

/** 请求序号：只有最新一次请求的响应才能更新页面状态 */
let requestSeq = 0

const load = async () => {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const res = await request.get('/admin/reports/summary', { params: { range: range.value } })
    if (seq !== requestSeq) return // 过期响应丢弃
    data.value = res
  } catch (e) {
    if (seq !== requestSeq) return
    error.value = e.message || '网络错误'
    data.value = null
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

const cards = computed(() => {
  const d = data.value
  if (!d) return []
  return [
    { label: '任务总数', value: d.total },
    { label: '进行中', value: d.doing },
    { label: '已完成', value: d.done },
    { label: '已过期', value: d.overdue },
    { label: '完成率', value: d.done + '/' + d.total, rate: d.completionRate },
    { label: '逾期率', value: d.overdue + '/' + d.total, rate: d.overdueRate }
  ]
})

onMounted(load)
</script>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
}
.stat-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  padding: 16px;
}
.stat-label { color: var(--color-text-muted); font-size: 13px; }
.stat-value { font-size: 28px; font-weight: 600; margin: 6px 0; }
.stat-rate { margin-top: 6px; font-size: 13px; color: var(--color-text-secondary); }
.rate-bar {
  height: 8px;
  background: var(--color-border);
  border-radius: 4px;
  overflow: hidden;
}
.rate-fill {
  height: 100%;
  background: var(--color-primary);
  border-radius: 4px;
  transition: width 0.3s;
}
</style>
```

（el-radio-button 用 `value` 属性——先确认 package.json 里 element-plus 版本 ≥2.6；若低于 2.6 则用 `label` 属性并保持语义一致。）

- [ ] **Step 2: 路由与守卫**

`frontend/src/router/index.js` children（notifications 之后）加：
```js
      { path: 'reports', name: 'admin-report', component: () => import('../views/ReportOverview.vue') }
```
守卫区（user-manage 那条之后）加：
```js
  // 报表仅管理员可访问
  if (to.name === 'admin-report' && loadRole() !== 'ADMIN') return '/tasks'
```

- [ ] **Step 3: 侧边栏菜单与标题**

`frontend/src/layout/Layout.vue`：桌面菜单与抽屉菜单两处（用户管理 item 之后）各加：
```html
        <el-menu-item v-if="auth.isAdmin" index="/reports"><el-icon><DataAnalysis /></el-icon><span>数据报表</span></el-menu-item>
```
icons import 加 `DataAnalysis`（并入现有 @element-plus/icons-vue import）；pageTitle map 加 `'/reports': '数据报表',`。

- [ ] **Step 4: 构建**

Run: `cd /Users/lijia/workspaces/java/task/frontend && npm run build`
Expected: 成功。

- [ ] **Step 5: Commit（仅前端 3 文件）**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && \
git add frontend/src/views/ReportOverview.vue \
        frontend/src/router/index.js \
        frontend/src/layout/Layout.vue && \
git commit -m "feat: 数据报表总览页与时间范围切换"
```

---

### Task 4: 无端口 mock 走查

**Files:**
- Create（/tmp，不入库）: `/tmp/task22_adminreport_playwright.py`

- [ ] **Step 1: 写 mock 脚本（完整文件）**

> **执行修正记录**（走查流程缺陷，与产品无关；以实际 /tmp/task22_adminreport_playwright.py 为准）：
> W3 切 leader1 前必须先走真实登出流程（user-chip → 退出登录）——登录态下访问 /login 会被路由守卫刻意弹回 /tasks（router/index.js:40 是产品行为），直接第二次 login() 永远拿不到登录表单。

```python
#!/usr/bin/env python3
"""Task 22 管理员报表 mock 走查（无端口）。

验证点：
  W1 admin 登录 → 侧边栏「数据报表」可见 → 打开 /reports → 6 卡片数字与比例条宽度正确
  W2 切换「近 7 天」→ 数字与比例条随之变化
  W3 leader1 登录 → 菜单无「数据报表」；直访 /reports 被守卫弹回 /tasks
"""
import json
import sys
from pathlib import Path
from urllib.parse import parse_qsl

from playwright.sync_api import sync_playwright

DIST = Path("/Users/lijia/workspaces/java/task/frontend/dist")
BASE = "http://app.test"
assert DIST.is_dir(), "先执行 npm run build"

# range → summary（整数比例已按后端口径算好）
SUMMARIES = {
    "all": {"total": 20, "doing": 7, "done": 8, "overdue": 5, "completionRate": 40, "overdueRate": 25},
    "7d":  {"total": 6,  "doing": 3, "done": 2, "overdue": 1, "completionRate": 33, "overdueRate": 16},
    "30d": {"total": 14, "doing": 5, "done": 6, "overdue": 3, "completionRate": 42, "overdueRate": 21},
}
USERS = {
    "admin": {"id": 1, "username": "admin", "realName": "系统管理员", "role": "ADMIN"},
    "leader1": {"id": 2, "username": "leader1", "realName": "李组长", "role": "LEADER"},
}


def handler(route):
    url = route.request.url
    if url.startswith(BASE + "/api/"):
        path = url[len(BASE):]
        qs = ""
        if "?" in path:
            path, qs = path.split("?", 1)
        if path == "/api/auth/login" and route.request.method == "POST":
            body = json.loads(route.request.post_data or "{}")
            u = USERS.get(body.get("username", ""))
            if not u:
                return route.fulfill(status=200, content_type="application/json",
                                     body=json.dumps({"code": 400, "message": "用户名或密码错误"}))
            return route.fulfill(status=200, content_type="application/json", body=json.dumps({
                "code": 0, "data": {"token": "t" + str(u["id"]),
                                    "user": dict(u, groupId=None, groupName=None)}}))
        if path == "/api/admin/reports/summary" and route.request.method == "GET":
            params = dict(parse_qsl(qs, keep_blank_values=True))
            r = params.get("range", "all")
            return route.fulfill(status=200, content_type="application/json", body=json.dumps({
                "code": 0, "data": SUMMARIES.get(r, SUMMARIES["all"])}))
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


def stat_values(page):
    return [page.locator(".stat-value").nth(i).inner_text() for i in range(6)]


def bar_width(page, i):
    return page.locator(".rate-fill").nth(i).evaluate("el => el.style.width")


def login(page, username, password):
    page.goto(BASE + "/login")
    page.get_by_placeholder("请输入用户名").fill(username)
    page.get_by_placeholder("请输入密码").fill(password)
    page.locator(".login-btn").click()
    page.wait_for_url("**/tasks")
    page.wait_for_timeout(500)


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1280, "height": 900})
        page.route("**/*", handler)
        page.set_default_timeout(8000)

        # W1 admin：菜单可见 + 卡片与比例条
        login(page, "admin", "admin123")
        ok_menu = page.locator(".el-menu-item", has_text="数据报表").count() >= 1
        page.locator(".el-menu-item", has_text="数据报表").first.click()
        page.wait_for_url("**/reports")
        page.wait_for_timeout(600)
        vals = stat_values(page)
        ok_w1 = ok_menu and vals == ["20", "7", "8", "5", "8/20", "5/20"] \
            and bar_width(page, 0) == "40%" and bar_width(page, 1) == "25%"
        record("W1", ok_w1, f"菜单可见+卡片数字/比例条正确（vals={vals}）")

        # W2 切换近 7 天
        page.locator(".el-radio-button", has_text="近 7 天").click()
        page.wait_for_timeout(600)
        vals2 = stat_values(page)
        ok_w2 = vals2 == ["6", "3", "2", "1", "2/6", "1/6"] \
            and bar_width(page, 0) == "33%" and bar_width(page, 1) == "16%"
        record("W2", ok_w2, f"切换近7天后数字/比例条更新（vals={vals2}）")

        # W3 leader1：菜单不可见 + 直访被守卫弹回
        login(page, "leader1", "123456")
        ok_w3a = page.locator(".el-menu-item", has_text="数据报表").count() == 0
        page.goto(BASE + "/reports")
        page.wait_for_timeout(600)
        ok_w3b = "/tasks" in page.url and "/reports" not in page.url
        record("W3", ok_w3a and ok_w3b, f"LEADER 无菜单且直访被弹回（a={ok_w3a} b={ok_w3b}）")

        browser.close()

    failed = [r for r in results if not r]
    print(f"\n==== 报表 mock 走查：{len(results) - len(failed)}/{len(results)} 通过 ====")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 运行走查**

Run: `cd /tmp && python3 task22_adminreport_playwright.py`
Expected: W1/W2/W3 全 ✓（3/3）。失败时先判断 mock 保真 vs 前端缺陷，不得弱化断言。

- [ ] **Step 3: 复跑既有 mock（回归）**

```bash
cd /tmp && python3 task19_pagination_playwright.py && python3 task20_mytasks_playwright.py && python3 task21_notification_playwright.py
```
Expected: 各通过（3/3、3/3、5/5）。文件缺失则报告并跳过，不重建。

---

### Task 5: 收尾与报告

- [ ] **Step 1: 全量回归**

- 后端：`cd /Users/lijia/workspaces/java/task/backend && mvn test` → 116 全绿（Docker 前置）
- 前端：`cd /Users/lijia/workspaces/java/task/frontend && npm run build` → 成功
- mock：task22 3/3、task19/20/21 回归通过

- [ ] **Step 2: 工作区检查与报告**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && git log --oneline -8 && git status --short
```
Expected: 工作区 clean（StartupBanner.java 为 tracked 且未修改，不得提交其修改）。

报告：commit SHA 列表、已提交文件、测试结果（AdminReportControllerTest 5/5、全量 116、build、mock）、残留风险、**不 push**（除非用户指示）。

---

## 遗留事项清单（终审记录，2026-09-08）

有意识接受/延后的项：

1. **三次 selectCount 非原子快照**：total/done/overdue 分三次查询，极端并发插入下理论 ±1 竞态；admin 低频接口，已接受（如需加固改单条原子聚合 SQL）。
2. **dev 库孤儿行**：9 行 task_member + 6 行 report + 808 行 notification 残留（历史遗留，非本次范围，未触碰）。
3. **报表精确断言依赖 task 表干净**：所有测试类自清且 seed 无任务，成立；若未来有并发测试或常驻数据需改基线相对断言。
4. **规格文档口径修正记录**：spec 曾写「无 @TableLogic（手动 deleted=1）」——实际为 application.yml 全局 `logic-delete-field: deleted`（selectCount 自动过滤、updateById 拒写该列）。代码显式 `eq(deleted,0)` 在两套机制下均正确（无害幂等谓词），零代码影响。
