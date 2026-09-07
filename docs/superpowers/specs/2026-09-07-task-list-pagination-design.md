# 任务列表分页 — 设计文档

日期：2026-09-07
分支：dev/task-system
来源：Task 18 收尾后的新特性提案（子项目 ①/④：① 分页 → ② 我的任务视图 → ③ 站内信 → ④ 管理员报表）

## 背景与目标

任务列表接口 `GET /api/tasks` 目前全量返回（`TaskServiceImpl.list` 无分页），前端 TaskList.vue 一次性渲染所有卡片。任务量增长后响应变慢、首屏卡顿。用户列表（`GET /api/users`）已有成熟的分页模式，本设计将任务列表对齐到同一模式。

目标：任务列表支持分页（数字翻页器），后端返回分页形状，前端分页交互与用户管理页一致。

## 范围

**做：**
- `GET /api/tasks` 分页参数与分页响应
- TaskList.vue 数字翻页器（el-pagination）
- 分页相关后端测试与前端 mock 走查

**不做（YAGNI）：**
- 待我审核 / 小组列表 / 用户候选人分页（均有天然上限或量级小）
- 每页条数用户可选（固定 12）
- 快照分页 / keyset 分页（翻页期间新插入任务导致的轻微重复可接受）
- 「已过期」维度（属于子项目 ②，本次不碰）

## 后端契约

`GET /api/tasks?type=&status=&keyword=&page=1&size=12`

- 新增参数：`page`（默认 1）、`size`（默认 12）
- 校验（与 `/api/users` 一致）：`page < 1 || size < 1 || size > 1000` → HTTP 200 业务 code 400，消息「分页参数不合法」
- 响应：`{code:0, data:{records: TaskVO[], total, page, size}}`（由 `Result<Page<TaskVO>>` 序列化；Page 来自 MyBatis-Plus `com.baomidou.mybatisplus.extension.plugins.pagination.Page`，与 UserVO 分页同款）
- 排序不变：`orderByDesc(id)`
- type（all/mine_created/assigned）、status、keyword 过滤逻辑原样保留，与分页叠加

实现位置：
- `TaskService.list` 签名改为 `Page<TaskVO> list(long page, long size, String type, String status, String keyword, SysUser cur)`
- `TaskServiceImpl.list`：`taskMapper.selectPage(new Page<>(page, size), qw)` → `toVO` 批量转换；`"assigned"` 分支的 `myTaskIds` 预查询不变；分页参数校验（page<1、size<1、size>1000 → 400）在 service，与 UserServiceImpl 完全一致
- `TaskController.list`：`@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "12") long size`（UserController.list 同款写法，默认值由 controller 兜底、合法性由 service 校验）

## 前端变更（TaskList.vue）

- 状态：`tasks` 改为分页响应（records + total）；保留现有 `page`、`size` 响应式值
- 底部新增 `el-pagination`：`layout="prev, pager, next, total"`，`v-if="total > query.size"`（与 UserManage.vue 同款），`@current-change="load"`
- 重置页码时机（重置后请求 page=1）：el-tabs `@tab-change`、状态筛选 `@change`、关键字输入 `@keyup.enter` 与 `@clear`（TaskList 无查询按钮，触发点即这三个）
- 每页固定 12；卡片网格布局不变
- 加载/空态/错误重试逻辑不动

## 测试与验证

后端（新建 `backend/src/test/java/com/task/controller/TaskControllerTest.java`，遵循 fixture 自建自清与依赖顺序物理 DELETE 约定）：
1. 非法分页参数 400：`page=0`、`size=-5`、`size=1001`
2. 分页正确性：admin/leader1 造 25 条任务，`page=2&size=10` 返回恰好 10 条、`total=25`、与 page=1 的记录不重不漏（按 id desc 验证边界）
3. 筛选与分页叠加：`status=DOING` + 分页，total 只含过滤后数量
4. 空页：page 超出范围返回空 records、total 正常，HTTP 200 code 0

前端（/tmp 无端口 mock 走查，沿用 task17/task18 脚本模式）：
- 造 25+ 条任务 → 首页 12 条、翻到第 2 页内容变化且正确
- 切 tab（我创建的/分配给我的）→ 回到第 1 页
- 状态筛选 + 搜索后再翻页正确

回归：后端全量测试、`npm run build`、既有 mock 脚本（task17/task18 等）复跑、`git diff --check`。

## 实施约束（沿用项目持久约束）

- 不 push（除非用户明确指示）；不执行迁移 SQL / schema.sql / smoke.sh；不连接真实 MinIO；不启动/停止 8080/5173；不用 npx / 不装依赖
- `backend/src/main/java/com/task/config/StartupBanner.java` 绝不提交；不与 backend 混提交无关前端文件
- 测试 fixture 自建自清；提交前 `git diff --check`；review-fix 用独立 fix commit
- 业务逻辑在 Service impl，Controller 薄层；分页参数校验与 users 实现保持一致

## 涉及文件

- `backend/src/main/java/com/task/service/TaskService.java`（接口签名）
- `backend/src/main/java/com/task/service/impl/TaskServiceImpl.java`（selectPage 实现）
- `backend/src/main/java/com/task/controller/TaskController.java`（参数）
- `backend/src/test/java/com/task/controller/TaskControllerTest.java`（新建，4 用例）
- `frontend/src/views/TaskList.vue`（分页交互）
