# 管理员报表（总览卡片）— 设计文档

日期：2026-09-08
分支：dev/task-system
来源：新特性提案子项目 ④/④（① 分页、② 我的任务视图、③ 站内信已完成；本项为最后一个子项目）

## 背景与目标

管理员需要任务总览报表：任务总数、进行中/已完成/已过期分布、完成率与逾期率，并支持按时间范围（全部/近 7 天/近 30 天）筛选。数据实时聚合（数据量小，不做预计算/缓存），不引入图表库（项目"不装依赖"约束），比例视觉用纯 CSS。

## 范围

**做：**
- `GET /api/admin/reports/summary?range=all|7d|30d` 聚合接口（仅 ADMIN）
- 前端「数据报表」页：时间范围切换 + 6 张卡片（4 数字卡 + 完成率/逾期率比例条）
- 侧边栏菜单（仅 ADMIN 可见）+ 路由守卫（非 ADMIN 回 /tasks）
- 后端测试 + 前端 mock 走查

**不做（YAGNI）：**
- 成员/小组维度表、趋势图、图表库、导出、缓存、按月统计
- 不新增数据库表（纯查询聚合，无迁移）

## 统计口径（四分类互斥，与列表 OVERDUE 完全一致）

- **总数** = 时间范围内**创建**的任务数（cohort 口径，按 `created_at` 过滤）
- **已完成** = `status = DONE`
- **已过期** = 未完成且 `deadline < now`（无 deadline 永不过期）
- **进行中** = 未完成且未过期 = 总数 − 已完成 − 已过期（三态互斥，合计恒等于总数）
- **完成率** = 已完成/总数 ×100（取整；总数为 0 时两率均为 0）
- **逾期率** = 已过期/总数 ×100
- `now` 用 Java 侧 `LocalDateTime.now()`（沿用既有 TZ 约定，与前端标红/列表 OVERDUE 一致）
- 逻辑删除任务自动排除（@TableLogic 既有机制，无需额外条件）

## 后端契约

- 新 `AdminReportController`：`@RequestMapping("/api/admin/reports")`，`GET /summary`，参数 `range`（`all`/`7d`/`30d`，默认 `all`，非法值业务 400「时间范围不合法」）
- 放在 `/api/admin/` 下，避免与现有 `/api/reports/{id}`（PUT/POST 后缀）路由歧义
- 服务层 `AdminReportService.reportSummary(range)`：`requireAdmin()`（非 ADMIN → 403，与 UserServiceImpl 同款）；用 MyBatis-Plus 条件计数：
  - total：`ge(created_at, from)`（from 为 null 时不加条件）
  - done：+ `eq(status, DONE)`
  - overdue：+ `ne(status, DONE)` + `lt(deadline, now)`
  - doing = total − done − overdue
- 响应 `ReportSummaryVO {total, doing, done, overdue, completionRate, overdueRate}`（rate 为 int 0-100）
- 7d/30d 的 from 计算：`LocalDateTime.now().minusDays(7/30)`

## 前端

- 路由 `frontend/src/router/index.js`：children 加 `{ path: 'reports', name: 'admin-report', component: () => import('../views/ReportOverview.vue') }`；守卫加：`if (to.name === 'admin-report' && loadRole() !== 'ADMIN') return '/tasks'`（与 user-manage 同款）
- 侧边栏 `Layout.vue`：`<el-menu-item v-if="auth.isAdmin" index="/reports"><el-icon><DataAnalysis /></el-icon><span>数据报表</span></el-menu-item>`（放在用户管理之后）；pageTitle 加 `'/reports': '数据报表'`
- 新页面 `frontend/src/views/ReportOverview.vue`：
  - 顶部 `el-radio-group`（全部/近 7 天/近 30 天）→ 切换重拉
  - 6 张卡片：总数、进行中、已完成、已过期（数字大字 + 占比小字）；完成率、逾期率（数字 + 纯 CSS 横向比例条，宽度 = rate%）
  - loading/error/重试/空态（总数为 0 时卡片显示 0 与 0%）、requestSeq 守卫照惯例
- 无新 API 依赖文件（直接复用 request）

## 测试与验证

后端（新 `AdminReportControllerTest`，fixture 自建自清）：
1. 口径四分类互斥：造已知组合（进行中×2、已过期×1、已完成×1、无 deadline 未完成×1）→ total=5、doing=3（含无 deadline 那条）、done=1、overdue=1、completionRate=20、overdueRate=20
2. 时间范围过滤：直插 created_at 为 10 天前/3 天前的任务（直插需覆盖 @TableLogic 默认与 created_at 字段）→ range=7d 只统计 3 天前的
3. 权限：zhangsan（EMPLOYEE）/leader1（LEADER）→ 403；admin → 0
4. 无任务时（range=7d 且范围内无任务）→ 全 0、两率 0
5. range 非法值 → 400

前端（无端口 mock 走查 task22）：
- admin 登录 → 数据报表页卡片数字与比例条宽度正确
- 切换时间范围 → 数字变化、比例条随之变化
- LEADER 登录访问 /reports → 被守卫弹回 /tasks

回归：后端全量（111 + 新增）、`npm run build`、既有 mock（task19/20/21）复跑、`git diff --check`。

## 实施约束（沿用项目持久约束）

- 不 push（除非用户指示）；不执行迁移/schema/smoke.sh（本特性无迁移）；不连接真实 MinIO；不启动/停止 8080/5173；不用 npx / 不装依赖
- `StartupBanner.java` 现为 tracked（仓库主人已提交），**不要修改它**；后端与前端分属独立 commit
- 测试 fixture 自建自清；提交前 `git diff --check`；review-fix 独立 commit、绝不 amend
- 业务逻辑在 Service impl，Controller 薄层

## 涉及文件

- Create: `backend/src/main/java/com/task/controller/AdminReportController.java`、`backend/src/main/java/com/task/service/AdminReportService.java`、`backend/src/main/java/com/task/service/impl/AdminReportServiceImpl.java`、`backend/src/main/java/com/task/vo/ReportSummaryVO.java`
- Create: `backend/src/test/java/com/task/controller/AdminReportControllerTest.java`
- Create: `frontend/src/views/ReportOverview.vue`
- Modify: `frontend/src/router/index.js`、`frontend/src/layout/Layout.vue`
