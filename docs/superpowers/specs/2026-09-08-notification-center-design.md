# 站内信（通知中心）— 设计文档

日期：2026-09-08
分支：dev/task-system
来源：新特性提案子项目 ③/④（① 分页、② 我的任务视图已完成；④ 管理员报表待做）

## 背景与目标

用户需要站内信：任务流转的关键事件（被分配任务、汇报被审核、有汇报待审核）以通知形式送达，支持未读角标、单条已读、全部已读、通知列表分页。不做实时推送（无 WebSocket/SSE），前端轮询未读数。

## 范围

**做：**
- notification 表 + 迁移脚本（CREATE TABLE，**经用户授权本次执行一次**）
- 四个触发事件：TASK_ASSIGNED / REPORT_SUBMITTED / REPORT_APPROVED / REPORT_REJECTED（同事务创建，跳过"自己通知自己"）
- 通知接口：分页列表 / 未读数 / 单条已读 / 全部已读（仅本人数据）
- 前端：顶栏铃铛（未读角标 + 最近 5 条下拉 + 全部已读 + 查看全部）+ 独立页 /notifications（分页、点击已读并跳转）
- 轮询未读数（30s）+ 后端/前端测试与 mock 走查

**不做（YAGNI）：**
- 不做聚合（单条一事）；不做评论/管理事件通知
- 不做 WebSocket/SSE；不做通知删除；不做通知偏好设置
- 不做页面隐藏时暂停轮询等优化（后续可加）

## 数据模型

新迁移脚本 `backend/src/main/resources/db/migration-2026-09-08-notification.sql`：

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

实体 `Notification`：`@TableName("notification")`，字段如上（isRead 用 `@TableLogic` 吗？**不**——is_read 是业务状态不是逻辑删除；逻辑删除概念不适用）。`id/userId/type/title/content/taskId/reportId/isRead/createdAt`。

## 后端契约（全部仅限本人，UserContext 校验）

- `GET /api/notifications?page=1&size=10` → `Page<NotificationVO>`：`{records,total,current,size}`，`orderByDesc(id)`；分页校验与 /api/tasks 一致（page≥1、size 1..1000，非法 400）
- `GET /api/notifications/unread-count` → `{count: N}`（`is_read=0` 计数）
- `PUT /api/notifications/{id}/read` → 单条已读；非本人 → 404（不泄露存在性）；已读幂等（重复调用 code=0）
- `PUT /api/notifications/read-all` → 本人全部未读置已读
- `NotificationVO`：id, type, title, content, taskId, reportId, read（boolean，is_read 映射）, createdAt

## 触发事件与插入点（同事务；自己通知自己一律跳过）

| 事件 | type | 插入点 | 接收人 | title / content |
|------|------|--------|--------|-----------------|
| 被分配任务 | `TASK_ASSIGNED` | TaskServiceImpl.create 成功后（成员确定之后） | 每个任务成员，**创建者本人除外**（个人任务创建者≠被分配者时通知被分配者；小组任务通知全部成员含其他组员，若创建者也是成员则跳过本人） | 「新任务分配」/ 你被分配了任务「{taskName}」 |
| 有汇报待审核 | `REPORT_SUBMITTED` | ReportServiceImpl.submit 与 resubmit 成功后 | 任务创建者；创建者=提交人时跳过 | 「待审核汇报」/ 任务「{taskName}」有新的汇报待你审核 |
| 汇报通过 | `REPORT_APPROVED` | ReportServiceImpl.approve 成功后 | 汇报提交人；提交人=审核人时跳过 | 「汇报已通过」/ 你的汇报（任务「{taskName}」）已通过，最终进度 {finalProgress}% |
| 汇报驳回 | `REPORT_REJECTED` | ReportServiceImpl.reject 成功后 | 汇报提交人；提交人=审核人时跳过 | 「汇报被驳回」/ 你的汇报（任务「{taskName}」）被驳回：{reviewComment} |

实现方式：新增 `NotificationService`（接口）+ `NotificationServiceImpl`，业务方法内注入并调用 `notificationService.notifyTaskAssigned(...)` 等语义化方法；插入为 `notificationMapper.insert`，随业务事务提交/回滚。

## 前端

- **Layout.vue 顶栏**：`.topbar-right` 内、用户下拉之前加铃铛（el-badge，value=unreadCount，hide-when-zero）+ el-popover 面板：
  - 最近 5 条（title + 时间 + 已读/未读视觉区分），点击 → 标记已读并跳转
  - 底部「全部已读」+「查看全部」（→ /notifications）
  - 轮询：onMounted 起 30s `setInterval` 拉 unread-count；onBeforeUnmount 清理
- **新页面 `frontend/src/views/NotificationCenter.vue`** + 路由 `/notifications`（name: `notifications`，登录可见，meta.title「消息中心」）：
  - 分页列表（复用任务列表的 Page 信封 + el-pagination 模式，size=10）
  - 行点击：PUT read → 跳转（taskId 存在 → `/tasks/{taskId}`；否则 → `/reports/pending`）
  - 顶部「全部已读」按钮（未读数>0 时可用）
  - 已读行置灰
- 新 API 模块 `frontend/src/api/notification.js`：listNotifications / unreadCount / markRead / markAllRead

## 测试与验证

后端（新 `NotificationControllerTest` + `NotificationServiceTest` 视需要）：
1. TASK_ASSIGNED：leader1 建小组/个人任务 → 成员（除创建者）各收到 1 条；创建者=被分配者（个人任务自派不可能——创建者≠被分配者时收到）与创建者=成员（小组任务）场景的跳过规则
2. REPORT_SUBMITTED：成员提交/重提汇报 → 创建者收到；创建者自己提交 → 不产生
3. REPORT_APPROVED / REPORT_REJECTED：审核后提交人收到（含驳回理由、最终进度文案）；提交人=审核人 → 不产生
4. 未读数 / 单条已读（幂等）/ 全部已读 / 分页校验
5. 越权：用户 A 对用户 B 的通知调 read → 404；列表只见自己的
6. fixture 自建自清（notification → report → task_member → task → sys_user 依赖顺序）

前端（无端口 mock 走查，新脚本 task21）：
- 角标显示未读数；下拉显示最近条；全部已读后角标消失
- 点击通知跳转 + 已读；独立页分页与已读置灰
- 轮询（mock 中把两次请求间的未读数变化注入验证角标更新）

回归：后端全量（102 + 新增）、`npm run build`、既有 mock（task19/task20）复跑、`git diff --check`。

## 实施约束（沿用项目持久约束 + 本次特批）

- **迁移执行**：用户已授权本次执行 `migration-2026-09-08-notification.sql` 一次（`docker exec mysql-dev mysql -uroot -proot123456 task_assign < ...`）；执行后测试才可运行。除本次外仍不执行任何迁移/schema。
- 不 push（除非用户指示）；不连接真实 MinIO；不启动/停止 8080/5173；不用 npx / 不装依赖
- `StartupBanner.java`、`backend/.vscode/` 绝不提交；后端与前端分属独立 commit
- 测试 fixture 自建自清（依赖顺序物理 DELETE）；提交前 `git diff --check`；review-fix 独立 commit、绝不 amend
- 业务逻辑在 Service impl，Controller 薄层

## 涉及文件

- Create: `backend/src/main/resources/db/migration-2026-09-08-notification.sql`
- Create: `backend/src/main/java/com/task/entity/Notification.java`、`mapper/NotificationMapper.java`、`vo/NotificationVO.java`、`service/NotificationService.java`、`service/impl/NotificationServiceImpl.java`、`controller/NotificationController.java`
- Modify: `TaskServiceImpl.java`（create 末尾）、`ReportServiceImpl.java`（submit/resubmit/approve/reject 末尾）
- Create: `backend/src/test/java/com/task/controller/NotificationControllerTest.java`
- Create: `frontend/src/api/notification.js`、`frontend/src/views/NotificationCenter.vue`
- Modify: `frontend/src/layout/Layout.vue`、`frontend/src/router/index.js`
