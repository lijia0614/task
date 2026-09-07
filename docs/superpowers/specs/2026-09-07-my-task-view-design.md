# 我的任务视图（状态维度扩展）— 设计文档

日期：2026-09-07
分支：dev/task-system
来源：新特性提案子项目 ②/④（① 分页已完成；③ 站内信、④ 管理员报表待做）

## 背景与目标

任务列表已有状态筛选（全部状态/进行中/已完成）与三个 tab（全部/我创建的/分配给我的）。用户需要「查看我的任务列表（已完成，未完成，已过期等）」——在现有列表框架上补齐三个状态维度，重点是新增**已过期**：逾期未交付的任务要能被筛选出来并优先排序。

## 范围

**做：**
- `GET /api/tasks` 的 status 参数新增 `OVERDUE` 值（后端翻译为条件 + 专属排序）
- 前端状态下拉扩展：全部状态 / 未完成 / 已完成 / 已过期（「进行中」仅改显示文案，wire 值 DOING 不变）
- 空态文案按筛选动态化（已过期筛选时「暂无已过期任务」）
- 后端 3 个测试用例 + 前端 mock 走查扩展

**不做（YAGNI）：**
- 不做「超期完成」标记（口径已定：完成后不算过期）
- 不改 wire 值 DOING/DONE，只改前端显示文案
- 不碰任务编辑/延期操作、不新增路由页、不新增 tab
- 逾期卡片的状态 tag 仍显示「进行中」（仅截止日期标红）——含 TaskDetail.vue 头部同款 tag（TaskDetail.vue:268），属已知后续打磨点
- 不动分页与重置页码逻辑（上一特性已就位，直接复用）

## 已过期判定口径（已确认）

- 条件：`status != DONE` **且** `deadline < now`（未完成且过了截止时间）
- 无 deadline 的任务永不过期（SQL `lt(deadline, now)` 对 NULL 不成立，自动排除）
- 完成后（DONE）不再算过期

## 后端契约

`GET /api/tasks?status=OVERDUE&...`（其余参数不变，分页契约不变）

- `status=OVERDUE` 时，查询条件：
  - `deadline < now`（Java 侧 `LocalDateTime.now()` 计算，**不用 SQL NOW()**）
  - `status != DONE`（用 `ne(DONE)` 而非 `eq(DOING)`，语义是"未完成"，未来新增状态不破坏）
- 排序：`OVERDUE` 时 `orderByAsc(deadline)`（逾期最久在前）+ 次键 `orderByDesc(id)`（同日截止时稳定去重）；其他 status 值保持 `orderByDesc(id)`
- `status` 其余值（空/DOING/DONE）行为与现状完全一致；非法值行为不变

**时区假设（显式记录）：** deadline 存为无时区的 `LocalDateTime`（墙钟时间）。过期判定以**应用服务器本地时区**为准（Java `LocalDateTime.now()`）；前端卡片标红（isOverdue）用浏览器本地时间。开发/部署在同一时区（+8）时两者一致；若未来跨时区部署，需引入时区规范（本次不做）。

## 前端变更（TaskList.vue）

- 状态下拉选项：
  - 全部状态 → `''`
  - 未完成 → `DOING`（原「进行中」改文案）
  - 已完成 → `DONE`
  - 已过期 → `OVERDUE`
- 空态文案动态化：`status === 'OVERDUE'` 时 el-empty description 显示「暂无已过期任务」，否则「暂无任务」
- 卡片 deadline 标红（现有 `isOverdue`）保持不动
- 分页/重置页码/stale-response 守卫零改动

## 测试与验证

后端（`TaskControllerTest` 增 3 用例，fixture 自建自清照旧）：
1. `overdueFilterReturnsOnlyUnfinishedPastDeadline`：构造四类任务——过期且未完成（命中）、未过期未完成（不命中）、过期但已完成（不命中）、无 deadline 未完成（不命中）；断言 `status=OVERDUE` 只返回第一类
2. `overdueSortsByDeadlineAsc`：≥3 条过期任务、不同过去 deadline，断言结果按 deadline 升序（最旧在前），同日按 id 倒序稳定
3. `overdueCombinesWithPagination`：25 条过期任务，page=2&size=10 → total=25、records=10、首条为第 11 旧 deadline

前端（扩展 /tmp/task19_pagination_playwright.py 或新建脚本，无端口 mock）：
- 选「已过期」→ 只显示过期卡片且按 deadline 升序
- 切回「未完成」→ 行为不变（回归）
- 已过期 + 空数据 → 「暂无已过期任务」

回归：后端全量（99+3）、`npm run build`、`git diff --check`、分页 mock 复跑。

## 实施约束（沿用项目持久约束）

- 不 push（除非用户指示）；不执行迁移 SQL / schema.sql / smoke.sh；不连接真实 MinIO；不启动/停止 8080/5173；不用 npx / 不装依赖
- `StartupBanner.java`、`backend/.vscode/` 绝不提交；后端与前端分属独立 commit
- 测试 fixture 自建自清（依赖顺序物理 DELETE）；提交前 `git diff --check`；review-fix 独立 commit、绝不 amend
- 业务逻辑在 Service impl，Controller 薄层

## 涉及文件

- `backend/src/main/java/com/task/service/impl/TaskServiceImpl.java`（OVERDUE 条件 + 排序）
- `backend/src/test/java/com/task/controller/TaskControllerTest.java`（+3 用例）
- `frontend/src/views/TaskList.vue`（下拉选项 + 空态文案）
- `/tmp` mock 脚本（扩展，不入库）
