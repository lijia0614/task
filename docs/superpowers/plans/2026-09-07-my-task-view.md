# 我的任务视图（已过期维度）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 任务列表新增「已过期」状态维度——`status=OVERDUE` 只返回未完成且过 deadline 的任务并按 deadline 升序；前端状态下拉改为 全部状态/未完成/已完成/已过期 + 空态文案动态化。

**Architecture:** 复用上一特性（分页）的全部框架。后端在 `TaskServiceImpl.list` 内把 `OVERDUE` 翻译为条件（`deadline < LocalDateTime.now()` && `status != DONE`）+ 专属排序（deadline 升序、id 倒序次键）；Java 侧算时间（不用 SQL NOW()，避免 MySQL 容器 UTC 与前端标红口径差 8 小时）。TDD：先失败测试，再实现。

**Tech Stack:** Spring Boot 3.2.5 / MyBatis-Plus 3.5.7 / MockMvc 集成测试（真实 MySQL）/ Vue 3 + Element Plus / Playwright mock（无端口）

**规格来源:** docs/superpowers/specs/2026-09-07-my-task-view-design.md

---

## 全局前置与持久约束（每一步都必须遵守）

- ⚠ **测试环境**：后端集成测试连真实 MySQL（docker 容器 `mysql-dev`）。每个 Run 步骤前确认 `docker info` 可用；不可用则**停止并询问用户**。
- 不 push（除非用户指示）；不执行迁移 SQL / schema.sql / smoke.sh；不连接真实 MinIO；不启动/停止 8080/5173；不用 npx / 不装依赖。
- `backend/src/main/java/com/task/config/StartupBanner.java` 绝不提交；`backend/.vscode/` 已在 .gitignore（勿提交勿删除）。
- 后端与前端分属独立 commit；测试 fixture 自建自清（依赖顺序物理 DELETE）；提交前 `git diff --check`；review-fix 独立 commit、绝不 amend。
- 工作目录 /Users/lijia/workspaces/java/task（bash 不保留 cwd，每条命令都要 `cd`）。

## 文件结构

- Modify: `backend/src/test/java/com/task/controller/TaskControllerTest.java` — +3 用例（OVERDUE 口径/排序/分页叠加）
- Modify: `backend/src/main/java/com/task/service/impl/TaskServiceImpl.java` — OVERDUE 条件 + 排序
- Modify: `frontend/src/views/TaskList.vue` — 下拉选项 + 空态文案
- Create（/tmp，不入库）: `/tmp/task20_mytasks_playwright.py`

---

### Task 1: 后端失败测试（RED）

**Files:**
- Modify: `backend/src/test/java/com/task/controller/TaskControllerTest.java`

- [ ] **Step 1: 加 helper（在现有 createTask 之后）**

现有 `createTask(token, name)` 不动；新增重载与直插 helper。imports 增加：`com.task.enums.TaskStatus`、`java.time.LocalDateTime`（`com.task.entity.Task` 已 import）。

```java
    /** leader1 建个人任务给 zhangsan，可带 deadline（已 JSON 序列化的 ISO 字符串，含引号）；登记清理 id；返回 taskId */
    private long createTask(String token, String name, String deadlineJson) throws Exception {
        SysUser zhangsan = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "zhangsan"));
        String body = mvc.perform(post("/api/tasks").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"assignType\":\"" + AssignType.INDIVIDUAL.getValue()
                                + "\",\"assigneeId\":" + zhangsan.getId()
                                + (deadlineJson == null ? "" : ",\"deadline\":" + deadlineJson) + "}"))
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

    /** 直插一条 DONE 任务（无 API 可置 DONE），登记清理 id。task 表非空列：name/creator_id/assign_type/assignee_id */
    private long insertDoneTask(String name, String deadlineJson) throws Exception {
        SysUser zhangsan = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "zhangsan"));
        SysUser leader1 = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "leader1"));
        Task t = new Task();
        t.setName(name);
        t.setCreatorId(leader1.getId());
        t.setAssignType(AssignType.INDIVIDUAL);
        t.setAssigneeId(zhangsan.getId());
        t.setStatus(TaskStatus.DONE);
        t.setDeadline(om.readValue(deadlineJson, LocalDateTime.class));
        t.setProgress(100);
        taskMapper.insert(t);
        createdTaskIds.add(t.getId());
        return t.getId();
    }
```

- [ ] **Step 2: 加 3 个测试（文件末尾 @AfterEach 之前）**

```java
    /** 已过期 = 未完成且 deadline 已过：四种构造只有第一种命中 */
    @Test
    void overdueFilterReturnsOnlyUnfinishedPastDeadline() throws Exception {
        String token = login("leader1", "123456");
        String p = "过期口径" + System.currentTimeMillis();
        String past = om.writeValueAsString(LocalDateTime.now().minusDays(7));
        String future = om.writeValueAsString(LocalDateTime.now().plusDays(30));
        createTask(token, p + "-过期未完成", past);     // 命中
        createTask(token, p + "-未过期未完成", future);  // 不命中（未来 deadline）
        insertDoneTask(p + "-过期已完成", past);          // 不命中（DONE）
        createTask(token, p + "-无截止未完成", null);     // 不命中（无 deadline）
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("status", "OVERDUE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value(p + "-过期未完成"));
    }

    /** 已过期按 deadline 升序（逾期最久在前），同日按 id 倒序稳定 */
    @Test
    void overdueSortsByDeadlineAsc() throws Exception {
        String token = login("leader1", "123456");
        String p = "过期排序" + System.currentTimeMillis();
        createTask(token, p + "-10d", om.writeValueAsString(LocalDateTime.now().minusDays(10)));
        createTask(token, p + "-30d", om.writeValueAsString(LocalDateTime.now().minusDays(30)));
        createTask(token, p + "-20d早", om.writeValueAsString(LocalDateTime.now().minusDays(20)));
        createTask(token, p + "-20d晚", om.writeValueAsString(LocalDateTime.now().minusDays(20)));
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("status", "OVERDUE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.records[0].name").value(p + "-30d"))
                .andExpect(jsonPath("$.data.records[1].name").value(p + "-20d晚"))
                .andExpect(jsonPath("$.data.records[2].name").value(p + "-20d早"))
                .andExpect(jsonPath("$.data.records[3].name").value(p + "-10d"));
    }

    /** 已过期与分页叠加：deadline 升序下 page2 首条 = 第 11 旧 */
    @Test
    void overdueCombinesWithPagination() throws Exception {
        String token = login("leader1", "123456");
        String p = "过期分页" + System.currentTimeMillis();
        for (int i = 1; i <= 25; i++) {
            createTask(token, p + i, om.writeValueAsString(LocalDateTime.now().minusDays(50 - i)));
        }
        // minusDays(50-i)：i=1 最旧（-49d）… i=25 最新（-25d）；升序后 page1=i1..i10，page2 首条=i11
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token)
                        .param("keyword", p).param("status", "OVERDUE").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.records.length()").value(10))
                .andExpect(jsonPath("$.data.records[0].name").value(p + "11"));
    }
```

- [ ] **Step 3: 运行验证失败（RED）**

前置：`docker info` 可用且 mysql-dev 在跑（不可用则停下问用户）。

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test -Dtest=TaskControllerTest`
Expected: FAIL —— 3 个新用例失败（当前 status=OVERDUE 走 `eq(status)` 过滤，无行匹配 → total=0，断言 total=1/4/25 失败）；既有 5 个用例仍 PASS。

---

### Task 2: 后端实现（GREEN）

**Files:**
- Modify: `backend/src/main/java/com/task/service/impl/TaskServiceImpl.java:178-196`

- [ ] **Step 1: 加 import**

在 `import java.util.Objects;` 之后加：
```java
import java.time.LocalDateTime;
```

- [ ] **Step 2: 替换 list 方法**

```java
    @Override
    public Page<TaskVO> list(long page, long size, String type, String status, String keyword, SysUser cur) {
        if (page < 1 || size < 1 || size > 1000) throw new BusinessException("分页参数不合法");
        List<Long> myTaskIds = memberMapper.selectList(new LambdaQueryWrapper<TaskMember>()
                        .eq(TaskMember::getUserId, cur.getId()))
                .stream().map(TaskMember::getTaskId).collect(Collectors.toList());

        boolean overdue = "OVERDUE".equals(status);
        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<Task>()
                .eq(!overdue && StringUtils.hasText(status), Task::getStatus, status)
                // 已过期：未完成且过了 deadline（Java 侧本地时间，与前端标红口径一致；不用 SQL NOW() 避免容器 UTC 偏差）
                .lt(overdue, Task::getDeadline, LocalDateTime.now())
                .ne(overdue, Task::getStatus, TaskStatus.DONE)
                .like(StringUtils.hasText(keyword), Task::getName, keyword);
        if (overdue) {
            qw.orderByAsc(Task::getDeadline).orderByDesc(Task::getId);
        } else {
            qw.orderByDesc(Task::getId);
        }
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

- [ ] **Step 3: 运行 TaskControllerTest（GREEN）**

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test -Dtest=TaskControllerTest`
Expected: PASS —— Tests run: 8, Failures: 0, Errors: 0（5 旧 + 3 新）。

- [ ] **Step 4: 后端全量回归**

Run: `cd /Users/lijia/workspaces/java/task/backend && mvn test`
Expected: 全绿（99 + 3 = 102，以实际输出为准）。

- [ ] **Step 5: Commit（仅 backend 2 文件）**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && \
git add backend/src/test/java/com/task/controller/TaskControllerTest.java \
        backend/src/main/java/com/task/service/impl/TaskServiceImpl.java && \
git status --short && \
git commit -m "feat: add OVERDUE filter with deadline-asc sorting to task list"
```
Expected: 工作区只剩 `?? backend/src/main/java/com/task/config/StartupBanner.java`。

---

### Task 3: 前端下拉与空态

**Files:**
- Modify: `frontend/src/views/TaskList.vue`

- [ ] **Step 1: 状态下拉（当前 13-16 行）**

```html
        <el-select v-model="status" class="status-select" placeholder="全部状态" clearable @change="resetPageAndLoad">
          <el-option label="进行中" value="DOING" />
          <el-option label="已完成" value="DONE" />
        </el-select>
```
替换为：
```html
        <el-select v-model="status" class="status-select" placeholder="全部状态" clearable @change="resetPageAndLoad">
          <el-option label="未完成" value="DOING" />
          <el-option label="已完成" value="DONE" />
          <el-option label="已过期" value="OVERDUE" />
        </el-select>
```

- [ ] **Step 2: 空态文案（当前 49-51 行区域）**

```html
        <el-empty description="暂无任务" :image-size="80" />
```
替换为：
```html
        <el-empty :description="status === 'OVERDUE' ? '暂无已过期任务' : '暂无任务'" :image-size="80" />
```

- [ ] **Step 3: 构建**

Run: `cd /Users/lijia/workspaces/java/task/frontend && npm run build`
Expected: 构建成功（dist 更新，供 Task 4 mock 使用）。

- [ ] **Step 4: Commit（仅前端 1 文件）**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && \
git add frontend/src/views/TaskList.vue && \
git commit -m "feat: add overdue option and dynamic empty text to task list filter"
```

---

### Task 4: 无端口 mock 走查

**Files:**
- Create（/tmp，一次性验证工具不入库）: `/tmp/task20_mytasks_playwright.py`

- [ ] **Step 1: 写 mock 脚本（完整文件）**

```python
#!/usr/bin/env python3
"""Task 20 我的任务视图（已过期）mock 走查（无端口）。

验证点：
  W1 选「已过期」→ 只显示未完成且过 deadline 的任务，按 deadline 升序（首卡=最旧）
  W2 切「未完成」→ 全部 DOING 任务可见（回归），首卡=id 最大（id desc）
  W3 已过期 + 无匹配关键字 → 空态文案「暂无已过期任务」

响应形状对齐后端：{code:0, data:{records,total,current,size}}；OVERDUE 口径与后端一致。
"""
import json
import sys
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import parse_qsl

from playwright.sync_api import sync_playwright

DIST = Path("/Users/lijia/workspaces/java/task/frontend/dist")
BASE = "http://app.test"
assert DIST.is_dir(), "先执行 npm run build"

NOW = datetime.now()
def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S")

# (id, name, status, deadline) —— deadline 为 None 表示无截止
FIXTURES = [
    (40, "未过期任务A", "DOING", NOW + timedelta(days=10)),
    (39, "过期最旧", "DOING", NOW - timedelta(days=30)),
    (38, "过期次新", "DOING", NOW - timedelta(days=10)),
    (37, "过期中间", "DOING", NOW - timedelta(days=20)),
    (36, "已完成任务X", "DONE", NOW - timedelta(days=5)),
    (35, "无截止任务Y", "DOING", None),
]
TASKS = [dict(id=i, name=n, description=None, creatorId=2, creatorName="李组长",
              assignType="INDIVIDUAL", assigneeId=2, assigneeName="李组长", status=s,
              deadline=(iso(d) if d else None), progress=0, doneAt=None,
              createdAt="2026-09-07T10:00:00", members=[], attachments=[])
         for (i, n, s, d) in FIXTURES]

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
            params = dict(parse_qsl(qs, keep_blank_values=True))
            kw = params.get("keyword", "")
            status = params.get("status", "")
            page, size = int(params.get("page", "1")), int(params.get("size", "12"))
            ts = [t for t in TASKS if not kw or kw in t["name"]]
            if status == "OVERDUE":
                ts = [t for t in ts
                      if t["status"] != "DONE" and t["deadline"] and t["deadline"] < iso(NOW)]
                ts.sort(key=lambda t: (t["deadline"], -t["id"]))  # deadline 升序，同日 id 倒序
            else:
                if status:
                    ts = [t for t in ts if t["status"] == status]
                ts.sort(key=lambda t: -t["id"])  # 其余场景 id 倒序
            start = (page - 1) * size
            body = {"code": 0, "data": {"records": ts[start:start + size],
                                        "total": len(ts), "current": page, "size": size}}
            return route.fulfill(status=200, content_type="application/json", body=json.dumps(body))
        return route.fulfill(status=200, content_type="application/json",
                             body=json.dumps({"code": 0, "data": None}))
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


def pick_status(page, label):
    page.locator(".status-select").click()
    page.locator(".el-select-dropdown:visible .el-select-dropdown__item",
                 has_text=label).first.click()


results = []


def record(w, ok, note):
    results.append(ok)
    print(f"{'✓' if ok else '✗'} {w}: {note}")


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
        page.wait_for_timeout(500)

        # W1 已过期：3 条（39 最旧/37/38），升序，首卡=过期最旧
        pick_status(page, "已过期")
        page.wait_for_timeout(500)
        cards = page.locator(".task-card")
        names = [cards.nth(i).locator(".task-name").inner_text() for i in range(cards.count())]
        ok_w1 = cards.count() == 3 and first_card(page) == "过期最旧" \
            and "未过期任务A" not in names and "已完成任务X" not in names and "无截止任务Y" not in names
        record("W1", ok_w1, f"已过期 3 条且按 deadline 升序（实际={names}）")

        # W2 未完成：全部 DOING 5 条，id 倒序，首卡=未过期任务A（id 40 最大）
        pick_status(page, "未完成")
        page.wait_for_timeout(500)
        ok_w2 = page.locator(".task-card").count() == 5 and first_card(page) == "未过期任务A" \
            and "已完成任务X" not in [page.locator(".task-card").nth(i).locator(".task-name").inner_text()
                                      for i in range(page.locator(".task-card").count())]
        record("W2", ok_w2, "未完成 5 条且 id 倒序（回归正常）")

        # W3 已过期 + 无匹配关键字 → 「暂无已过期任务」
        page.locator(".kw-input input").fill("不存在的关键字")
        page.locator(".kw-input input").press("Enter")
        page.wait_for_timeout(500)
        ok_w3 = page.locator(".el-empty", has_text="暂无已过期任务").count() == 1
        record("W3", ok_w3, "空态文案「暂无已过期任务」")

        browser.close()

    failed = [r for r in results if not r]
    print(f"\n==== 我的任务视图 mock 走查：{len(results) - len(failed)}/{len(results)} 通过 ====")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 运行走查**

Run: `cd /tmp && python3 task20_mytasks_playwright.py`
Expected:
```
✓ W1: 已过期 3 条且按 deadline 升序（实际=['过期最旧', '过期中间', '过期次新']）
✓ W2: 未完成 5 条且 id 倒序（回归正常）
✓ W3: 空态文案「暂无已过期任务」
==== 我的任务视图 mock 走查：3/3 通过 ====
```
（若失败，先判断是 mock 问题还是真实前端问题——口径/排序/文案均已对齐规格，不得弱化断言；查清后如实报告。）

- [ ] **Step 3: 复跑分页 mock（回归）**

Run: `cd /tmp && python3 task19_pagination_playwright.py`
Expected: 3/3（分页特性不回归；该脚本的 mock 未实现 OVERDUE，但前端改动不影响其场景）。

---

### Task 5: 收尾与报告

- [ ] **Step 1: 全量回归**

- 后端：`cd /Users/lijia/workspaces/java/task/backend && mvn test` → 102 全绿（Docker 前置）
- 前端：`cd /Users/lijia/workspaces/java/task/frontend && npm run build` → 成功
- mock：task20 3/3、task19 3/3

- [ ] **Step 2: 工作区检查与报告**

```bash
cd /Users/lijia/workspaces/java/task && git diff --check && git log --oneline -6 && git status --short
```
Expected: 工作区仅 `?? backend/src/main/java/com/task/config/StartupBanner.java`（绝不提交）。

报告内容：commit SHA 列表、已提交文件、测试结果（TaskControllerTest 8/8、全量 102、build、task20 3/3、task19 3/3）、残留风险、**不 push**（除非用户指示）。
