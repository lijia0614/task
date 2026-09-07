# 任务列表分页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 `GET /api/tasks` 与任务列表页增加分页（MyBatis-Plus Page + el-pagination 数字翻页器），与用户管理页模式一致。

**Architecture:** 后端沿用 UserServiceImpl 已验证的 `selectPage` → `Page<TaskVO>` 转换模式；前端 TaskList.vue 维护 page/total 状态，筛选条件变化重置回第 1 页，stale-response 保护（requestSeq）原样保留。TDD：先写失败测试，再实现。

**Tech Stack:** Spring Boot 3.2.5 / MyBatis-Plus 3.5.7（Page）/ MockMvc 集成测试 / Vue 3 + Element Plus（el-pagination）/ Playwright mock（无端口）

**规格来源:** docs/superpowers/specs/2026-09-07-task-list-pagination-design.md

---

## 全局前置与持久约束（每一步都必须遵守）

- ⚠ **测试环境**：后端集成测试连真实 MySQL（docker 容器 `mysql-dev`）。**当前 Docker daemon DOWN** —— 每个 Run 步骤执行前先确认 `docker info` 可用；若不可用，**停止并询问用户**（Task 17 先例：由用户确认测试结果）。
- 不 push（除非用户明确指示）；不执行迁移 SQL / schema.sql / smoke.sh；不连接真实 MinIO；不启动/停止 8080/5173；不用 npx / 不装依赖。
- `backend/src/main/java/com/task/config/StartupBanner.java` 未跟踪，**绝不提交**；`backend/.vscode/` 未跟踪，绝不提交。
- 后端与前端分属独立 commit；每个测试只建并清理自己的 fixture（依赖顺序物理 DELETE）；提交前 `git diff --check`。
- 工作目录 /Users/lijia/workspaces/java/task（bash 不保留 cwd，每条命令都要 `cd`）。

## 文件结构

- Create: `backend/src/test/java/com/task/controller/TaskControllerTest.java` — 分页契约测试（4 用例，fixture 自建自清）
- Modify: `backend/src/main/java/com/task/service/TaskService.java` — list 签名改 Page
- Modify: `backend/src/main/java/com/task/service/impl/TaskServiceImpl.java` — selectPage 实现
- Modify: `backend/src/main/java/com/task/controller/TaskController.java` — page/size 参数
- Modify: `frontend/src/views/TaskList.vue` — records/total 状态 + el-pagination
- Modify: `.gitignore` — 追加 `backend/.vscode/`
- Create（一次性验证工具，不入库）: `/tmp/task19_pagination_playwright.py`

---

### Task 1: 后端分页失败测试（RED）

**Files:**
- Create: `backend/src/test/java/com/task/controller/TaskControllerTest.java`

- [ ] **Step 1: 写失败测试（完整文件）**

```java
package com.task.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.task.TaskApplication;
import com.task.entity.SysUser;
import com.task.entity.Task;
import com.task.entity.TaskMember;
import com.task.enums.AssignType;
import com.task.mapper.SysUserMapper;
import com.task.mapper.TaskMapper;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 任务列表分页契约（Task 19）：非法参数 400、分页正确性、筛选叠加、空页 */
@SpringBootTest(classes = TaskApplication.class)
@AutoConfigureMockMvc
class TaskControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired TaskMapper taskMapper;
    @Autowired TaskMemberMapper memberMapper;
    @Autowired SysUserMapper userMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 本用例自建数据 id（按依赖顺序物理清理：task_member → task，不动种子数据） */
    private final List<Long> createdTaskIds = new ArrayList<>();
    private final List<Long> createdMemberIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : createdMemberIds) jdbcTemplate.update("DELETE FROM task_member WHERE id = ?", id);
        for (Long id : createdTaskIds) jdbcTemplate.update("DELETE FROM task WHERE id = ?", id);
        createdMemberIds.clear();
        createdTaskIds.clear();
    }

    private String login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(java.util.Map.of("username", username, "password", password))))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    /** leader1 建个人任务给种子用户 zhangsan；登记 task 及其全部 task_member id 供清理；返回 taskId */
    private long createTask(String token, String name) throws Exception {
        SysUser zhangsan = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "zhangsan"));
        String body = mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"assignType\":\"" + AssignType.INDIVIDUAL.getValue()
                                + "\",\"assigneeId\":" + zhangsan.getId() + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        long taskId = Long.parseLong(body.replaceAll(".*\"data\":(\\d+).*", "$1"));
        createdTaskIds.add(taskId);
        for (TaskMember tm : memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                .eq(TaskMember::getTaskId, taskId))) {
            createdMemberIds.add(tm.getId());
        }
        return taskId;
    }

    /** 分页参数非法（page<1、size<1、size>1000）必须业务 400；契约与 /api/users 一致 */
    @Test
    void invalidPagingParamsReturn400() throws Exception {
        String token = login("admin", "admin123");
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token).param("page", "0"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token).param("size", "-5"))
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token).param("size", "1001"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /** 25 条任务：page=2&size=10 恰好 10 条、total=25、两页不重不漏（按 id desc 验证边界） */
    @Test
    void paginationReturnsExactPageAndTotal() throws Exception {
        String token = login("leader1", "123456");
        String p = "分页验收" + System.currentTimeMillis();
        for (int i = 1; i <= 25; i++) createTask(token, p + i);

        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.current").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(10))
                // id desc：25..16 在第 1 页，第 2 页首条 = p15
                .andExpect(jsonPath("$.data.records[0].name").value(p + "15"));

        // 第 1 页首条 = p25，且两页 20 条记录互不重复
        String page1 = mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("page", "1").param("size", "10"))
                .andExpect(jsonPath("$.data.records[0].name").value(p + "25"))
                .andReturn().getResponse().getContentAsString();
        String page2 = mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("page", "2").param("size", "10"))
                .andReturn().getResponse().getContentAsString();
        Set<String> names = new HashSet<>();
        for (String body : List.of(page1, page2)) {
            JsonNode tree = om.readTree(body);
            for (JsonNode n : tree.path("data").path("records")) names.add(n.path("name").asText());
        }
        assertEquals(20, names.size(), "两页记录必须不重不漏");
    }

    /** 关键字 + 状态筛选与分页叠加：total 只含过滤后数量 */
    @Test
    void filterCombinesWithPagination() throws Exception {
        String token = login("leader1", "123456");
        String p = "分页验收" + System.currentTimeMillis();
        for (int i = 1; i <= 25; i++) createTask(token, p + i);
        // keyword=p+1 匹配 p1、p10..p19 共 11 条；page=2&size=5 首条 = p14（id desc：19..15 第 1 页）
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p + "1").param("status", "DOING").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.records.length()").value(5))
                .andExpect(jsonPath("$.data.records[0].name").value(p + "14"));
    }

    /** 页码超出范围：返回空 records、total 正常，不报错 */
    @Test
    void emptyPageReturnsEmptyRecords() throws Exception {
        String token = login("leader1", "123456");
        String p = "分页验收" + System.currentTimeMillis();
        for (int i = 1; i <= 25; i++) createTask(token, p + i);
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("page", "99").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }
}
```

- [ ] **Step 2: 运行测试验证失败（RED）**

前置：`docker info` 可用且 mysql-dev 容器在跑（当前 DOWN，不可用则停下问用户）。

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test -Dtest=TaskControllerTest`
Expected: FAIL —— `invalidPagingParamsReturn400` 失败（当前接口无视 page/size，page=0 返回 code 0）；其余三个失败（当前响应是数组，`$.data.total` 不存在）。

---

### Task 2: 后端分页实现（GREEN）

**Files:**
- Modify: `backend/src/main/java/com/task/service/TaskService.java`
- Modify: `backend/src/main/java/com/task/service/impl/TaskServiceImpl.java:178-196`
- Modify: `backend/src/main/java/com/task/controller/TaskController.java:26-32`

- [ ] **Step 1: TaskService 接口签名**

当前（第 13 行）：
```java
    List<TaskVO> list(String type, String status, String keyword, SysUser cur);
```
改为：
```java
    Page<TaskVO> list(long page, long size, String type, String status, String keyword, SysUser cur);
```
imports 区在 `import com.task.vo.TaskVO;` 之后加一行：
```java
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
```
（`java.util.List` 保留——`updateWeights` 还在用。）

- [ ] **Step 2: TaskServiceImpl 实现**

imports：删除第 25 行 `import java.util.Collections;`（改动后唯一使用处消失）；在 `import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;` 之后加：
```java
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
```

将 `list` 方法整体替换为：
```java
    @Override
    public Page<TaskVO> list(long page, long size, String type, String status, String keyword, SysUser cur) {
        if (page < 1 || size < 1 || size > 1000) throw new BusinessException("分页参数不合法");
        List<Long> myTaskIds = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                        .eq(TaskMember::getUserId, cur.getId()))
                .stream().map(TaskMember::getTaskId).collect(Collectors.toList());

        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<Task>()
                .eq(StringUtils.hasText(status), Task::getStatus, status)
                .like(StringUtils.hasText(keyword), Task::getName, keyword)
                .orderByDesc(Task::getId);
        if ("mine_created".equals(type)) {
            qw.eq(Task::getCreatorId, cur.getId());
        } else if ("assigned".equals(type)) {
            if (myTaskIds.isEmpty()) return new Page<>(page, size);
            qw.in(Task::getId, myTaskIds);
        }
        Page<Task> p = taskMapper.selectPage(new Page<>(page, size), qw);
        Page<TaskVO> voPage = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        voPage.setRecords(p.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
        return voPage;
    }
```

- [ ] **Step 3: TaskController 参数**

imports 区 `import com.task.vo.TaskVO;` 之后加：
```java
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
```

将 `list` 方法替换为：
```java
    @GetMapping
    public Result<Page<TaskVO>> list(@RequestParam(defaultValue = "1") long page,
                                     @RequestParam(defaultValue = "12") long size,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String keyword) {
        return Result.ok(taskService.list(page, size, type, status, keyword, UserContext.get()));
    }
```
（`java.util.List` 保留——`updateWeights` 还在用。）

- [ ] **Step 4: 运行 TaskControllerTest 验证通过（GREEN）**

前置：Docker/mysql-dev 可用（不可用则停下问用户）。

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test -Dtest=TaskControllerTest`
Expected: PASS —— Tests run: 4, Failures: 0, Errors: 0。

- [ ] **Step 5: 后端全量回归**

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test`
Expected: 全绿（原有 94 例 + 新增 4 例，以实际输出为准）。

- [ ] **Step 6: Commit（仅后端 4 个文件）**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && \
git add backend/src/test/java/com/task/controller/TaskControllerTest.java \
        backend/src/main/java/com/task/service/TaskService.java \
        backend/src/main/java/com/task/service/impl/TaskServiceImpl.java \
        backend/src/main/java/com/task/controller/TaskController.java && \
git status --short && \
git commit -m "feat: paginate task list API"
```
Expected: commit 成功，`git status --short` 只剩 `?? backend/.vscode/` 与 `?? backend/src/main/java/com/task/config/StartupBanner.java`。

---

### Task 3: 前端任务列表分页交互

**Files:**
- Modify: `frontend/src/views/TaskList.vue`

- [ ] **Step 1: 模板改动（3 处精确替换）**

① 筛选触发改为重置页码（第 5-15 行区域）：

```html
      <el-tabs v-model="activeTab" @tab-change="load">
```
改为：
```html
      <el-tabs v-model="activeTab" @tab-change="resetPageAndLoad">
```

```html
        <el-input v-model="keyword" class="kw-input" placeholder="搜索任务名称" clearable
                  :prefix-icon="Search" @keyup.enter="load" @clear="load" />
```
改为：
```html
        <el-input v-model="keyword" class="kw-input" placeholder="搜索任务名称" clearable
                  :prefix-icon="Search" @keyup.enter="resetPageAndLoad" @clear="resetPageAndLoad" />
```

```html
        <el-select v-model="status" class="status-select" placeholder="全部状态" clearable @change="load">
```
改为：
```html
        <el-select v-model="status" class="status-select" placeholder="全部状态" clearable @change="resetPageAndLoad">
```

② 列表数据源改 records：

```html
      <div v-else-if="!tasks.length" class="section center-box">
```
改为：
```html
      <div v-else-if="!records.length" class="section center-box">
```

```html
        <div v-for="t in tasks" :key="t.id" class="task-card" tabindex="0" role="link"
```
改为：
```html
        <div v-for="t in records" :key="t.id" class="task-card" tabindex="0" role="link"
```

③ 模板末尾（card-grid 与 list-body 两个闭合 `</div>` 之后、外层 `</div>` 之前）插入翻页器：

```html
        </div>
      </div>
    </div>
  </div>
</template>
```
改为：
```html
        </div>
      </div>
    </div>

    <!-- 分页：总数超过一页时显示（与用户管理页同款交互） -->
    <el-pagination v-if="total > pageSize" class="task-pagination" layout="prev, pager, next, total"
                   v-model:current-page="page" :page-size="pageSize" :total="total"
                   @current-change="load" />
  </div>
</template>
```

- [ ] **Step 2: script 改动（状态与 load）**

将状态声明段：
```js
const activeTab = ref('all')
const keyword = ref('')
const status = ref('')
const tasks = ref([])
const loading = ref(false)
const error = ref('')
```
改为：
```js
const activeTab = ref('all')
const keyword = ref('')
const status = ref('')
const records = ref([])   // 当前页记录（分页响应 records）
const total = ref(0)      // 总数（分页响应 total）
const page = ref(1)
const pageSize = 12
const loading = ref(false)
const error = ref('')
```

将 `load` 方法替换为：
```js
const load = async () => {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const data = await listTasks({
      type: activeTab.value,
      status: status.value || undefined,
      keyword: keyword.value || undefined,
      page: page.value,
      size: pageSize
    })
    if (seq !== requestSeq) return // 过期响应丢弃
    records.value = data.records
    total.value = data.total
  } catch (e) {
    if (seq !== requestSeq) return // 过期失败也丢弃
    error.value = e.message || '网络错误'
    records.value = []
    total.value = 0
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

/** 筛选条件变化：回到第 1 页再加载 */
const resetPageAndLoad = () => {
  page.value = 1
  load()
}
```

- [ ] **Step 3: 样式追加**

`<style scoped>` 内末尾追加：
```css
.task-pagination { display: flex; justify-content: center; margin-top: 16px; }
```

- [ ] **Step 4: 构建验证**

Run: `cd /Users/lijia/workspaces/java/task/frontend && npm run build`
Expected: 构建成功（dist 更新，供 Task 4 mock 使用）。

- [ ] **Step 5: Commit（仅前端 1 个文件）**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && \
git add frontend/src/views/TaskList.vue && \
git commit -m "feat: paginate task list page"
```

---

### Task 4: 无端口 mock 走查（分页交互验证）

**Files:**
- Create（/tmp，一次性验证工具不入库）: `/tmp/task19_pagination_playwright.py`

- [ ] **Step 1: 写 mock 脚本（完整文件）**

```python
#!/usr/bin/env python3
"""Task 19 任务列表分页 mock 走查（无端口）：数字翻页 + 切 tab 重置 + 筛选重置。

验证点：
  V1 全部 30 条：首页 12 条、首卡=验收任务30、翻页器含「共 30 条」；点第 2 页 → 首卡=验收任务18
  V2 切「分配给我的」→ 回到第 1 页（pager 高亮 1、首卡=验收任务30）
  V3 关键字「验收任务1」→ 11 条匹配 ≤12，翻页器隐藏

业务规则与响应形状对齐后端：{code:0, data:{records,total,current,size}}，id desc 排序。
"""
import json
import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

DIST = Path("/Users/lijia/workspaces/java/task/frontend/dist")
BASE = "http://app.test"
assert DIST.is_dir(), "先执行 npm run build"

# id 30..1；偶数 id 分配给 leader1（id=2），奇数给 zhangsan（id=3）
# 注意：名字不零填充——关键字「验收任务1」需匹配 1、10..19 共 11 条
TASKS = [dict(id=i, name=f"验收任务{i}", description=None, creatorId=2, creatorName="李组长",
              assignType="INDIVIDUAL", assigneeId=(2 if i % 2 == 0 else 3),
              assigneeName=("李组长" if i % 2 == 0 else "张三"), status="DOING",
              deadline=None, progress=0, doneAt=None, createdAt="2026-09-07T10:00:00",
              members=[], attachments=[])
         for i in range(30, 0, -1)]  # 已按 id desc

MIME = {".js": "text/javascript", ".css": "text/css", ".html": "text/html",
        ".svg": "image/svg+xml", ".png": "image/png"}


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
        if path == "/api/tasks" and route.request.method == "GET":
            params = dict(re.findall(r"([^=&]+)=([^&]*)", qs))
            typ = params.get("type", "all")
            kw = params.get("keyword", "")
            page, size = int(params.get("page", "1")), int(params.get("size", "12"))
            ts = [t for t in TASKS if not kw or kw in t["name"]]
            if typ == "mine_created":
                ts = [t for t in ts if t["creatorId"] == 2]
            elif typ == "assigned":
                ts = [t for t in ts if t["assigneeId"] == 2]
            start = (page - 1) * size
            body = {"code": 0, "data": {"records": ts[start:start + size],
                                        "total": len(ts), "current": page, "size": size}}
            return route.fulfill(status=200, content_type="application/json", body=json.dumps(body))
        return route.fulfill(status=200, content_type="application/json",
                             body=json.dumps({"code": 0, "data": None}))
    # 静态资源：SPA 路径回退 index.html
    p = url[len(BASE):].split("?", 1)[0]
    f = DIST / (p.lstrip("/") or "index.html")
    if not f.exists() or f.is_dir():
        f = DIST / "index.html"
    if f.is_dir():
        f = f / "index.html"
    route.fulfill(status=200, content_type=MIME.get(f.suffix.lower(), "application/octet-stream"),
                  body=f.read_bytes())


def first_card(page):
    return page.locator(".task-card").first.locator(".task-name").inner_text()


results = []


def record(v, ok, note):
    results.append(ok)
    print(f"{'✓' if ok else '✗'} {v}: {note}")


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch()
        page = browser.new_page(viewport={"width": 1280, "height": 900})
        page.route("**/*", handler)
        page.set_default_timeout(8000)

        # 登录 leader1
        page.goto(BASE + "/login")
        page.get_by_placeholder("请输入用户名").fill("leader1")
        page.get_by_placeholder("请输入密码").fill("123456")
        page.locator(".login-btn").click()
        page.wait_for_url("**/tasks")
        page.wait_for_timeout(500)

        # V1 首页 12 条 + 翻页
        ok_v1a = page.locator(".task-card").count() == 12 and first_card(page) == "验收任务30"
        ok_v1b = "共 30 条" in page.locator(".el-pagination__total").inner_text()
        page.locator(".el-pager li", has_text="2").first.click()
        page.wait_for_timeout(500)
        ok_v1c = first_card(page) == "验收任务18"
        record("V1", ok_v1a and ok_v1b and ok_v1c,
               f"首页12条/首卡30/共30条/第2页首卡18（a={ok_v1a} b={ok_v1b} c={ok_v1c}）")

        # V2 切 tab 回第 1 页
        page.get_by_role("tab", name="分配给我的").click()
        page.wait_for_timeout(500)
        ok_v2 = first_card(page) == "验收任务30" \
            and page.locator(".el-pager li.is-active").inner_text() == "1"
        record("V2", ok_v2, "切「分配给我的」后回到第 1 页且首卡=验收任务30")

        # V3 关键字筛选后翻页器隐藏
        page.get_by_role("tab", name="全部").click()
        page.wait_for_timeout(300)
        page.locator(".kw-input input").fill("验收任务1")
        page.locator(".kw-input input").press("Enter")
        page.wait_for_timeout(500)
        ok_v3 = page.locator(".task-card").count() == 11 \
            and page.locator(".el-pagination").count() == 0
        record("V3", ok_v3, "关键字匹配 11 条且翻页器隐藏（单页不显示）")

        browser.close()

    failed = [r for r in results if not r]
    print(f"\n==== 分页 mock 走查：{len(results) - len(failed)}/{len(results)} 通过 ====")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 运行走查**

Run: `cd /tmp && python3 task19_pagination_playwright.py`
Expected:
```
✓ V1: 首页12条/首卡30/共30条/第2页首卡18（a=True b=True c=True）
✓ V2: 切「分配给我的」后回到第 1 页且首卡=验收任务30
✓ V3: 关键字匹配 11 条且翻页器隐藏（单页不显示）
==== 分页 mock 走查：3/3 通过 ====
```

说明：先前 task17/task18 的 mock 脚本存放在 /tmp，已被系统清理（/tmp 非持久）；其结论已记录在 docs/superpowers/verification/2026-08-01-e2e.md，未改动流程（汇报/审核/评论等）由后端全量集成测试背书，无需重建。

---

### Task 5: 收尾与回归

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: .gitignore 追加 IDE 目录**

Run:
```bash
cd /Users/lijia/workspaces/java/task && echo 'backend/.vscode/' >> .gitignore && git diff --check && git status --short
```
Expected: `backend/.vscode/` 不再出现在未跟踪列表。

- [ ] **Step 2: 全量回归**

- 后端：`cd /Users/lijia/workspaces/java/task/backend && mvn test` → 全绿（Docker 前置，不可用则停下问用户）
- 前端：`cd /Users/lijia/workspaces/java/task/frontend && npm run build` → 成功（Task 3 已跑过，可跳过）
- 分页 mock：Task 4 已 3/3

- [ ] **Step 3: 提交 .gitignore**

```bash
cd /Users/lijia/workspaces/java/task && git add .gitignore && \
git commit -m "chore: ignore backend IDE config" && \
git log --oneline -6 && git status --short
```
Expected: 提交后工作区仅剩 `?? backend/src/main/java/com/task/config/StartupBanner.java`（绝不提交）。

- [ ] **Step 4: 报告**

报告内容：commit SHA 列表、已提交文件、测试结果（TaskControllerTest 4/4、全量、build、mock 3/3）、残留风险（无新风险；快照分页不做属已批准范围）、**不 push**（除非用户指示）。
