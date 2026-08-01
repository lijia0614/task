# 任务分配系统 — 设计文档

日期：2026-08-01
状态：已确认

## 1. 项目概述

公司内部任务分配系统：给员工（个人或小组）分配任务，员工汇报进度，分配者审核，支持任务附件、任务评论/回复、小组管理（组长 + 组员权重）。

**技术栈：**

- 后端：Java 17 + Spring Boot 3.x + MyBatis-Plus + MySQL 8.0 + jjwt（JWT 认证）+ MinIO（附件存储）
- 前端：Vue 3 + Vite + Element Plus + Vue Router + Pinia + Axios
- 环境：MySQL 8.0.46（Docker `mysql-dev`，root/root123456，3306）、MinIO（`minio-dev`，9000 端口）、Node 18

**目录结构：**

```
/Users/lijia/workspaces/java/task/
├── backend/      # Spring Boot 单体后端
├── frontend/     # Vue 3 前端
└── docs/         # 设计文档
```

## 2. 角色与权限

三级角色：`EMPLOYEE`（员工）、`LEADER`（组长）、`ADMIN`（管理员）。

| 操作 | 员工 | 组长 | 管理员 |
|---|---|---|---|
| 查看分配给自己的任务、提交汇报、评论 | ✓ | ✓ | ✓ |
| 查看自己小组的任务/汇报 | ✓（本组） | ✓ | ✓ |
| 创建任务、调整成员权重 | ✗ | ✓ | ✓ |
| 审核汇报（仅任务分配者） | ✗ | ✓ | ✓ |
| 管理小组 | ✗ | 仅自己的组 | 所有组 |
| 用户管理 | ✗ | ✗ | ✓ |

- 审核人 = 任务创建者（分配者）；管理员也可审核任意任务汇报。
- 权限校验：登录拦截器解析 JWT 存当前用户上下文，Controller 层用注解/切面校验角色与归属。

## 3. 数据库设计（8 张表，库名 `task_assign`）

核心思想：**个人/小组任务统一展开为 `task_member` 记录**，汇报、进度、权重全部挂在成员上，一套逻辑处理两种分配。

### 3.1 sys_user（用户）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK AUTO | |
| username | VARCHAR(50) UNIQUE | 登录名 |
| password | VARCHAR(100) | BCrypt 加密 |
| real_name | VARCHAR(50) | 姓名 |
| role | VARCHAR(20) | EMPLOYEE / LEADER / ADMIN |
| group_id | BIGINT NULL | 所属小组，空 = 无组；组长身份由 sys_group.leader_id 识别 |
| created_at / updated_at | DATETIME | |

### 3.2 sys_group（小组）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| name | VARCHAR(50) | 组名 |
| leader_id | BIGINT | 组长（sys_user.id），一个小组一个组长 |
| description | VARCHAR(255) | |
| created_at / updated_at | DATETIME | |

### 3.3 task（任务）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| name | VARCHAR(100) | 任务名称 |
| description | TEXT | 任务简介 |
| creator_id | BIGINT | 创建者 = 分配者（审核人） |
| assign_type | VARCHAR(20) | INDIVIDUAL / GROUP |
| assignee_id | BIGINT | 个人 = 用户 id；小组 = 组 id |
| status | VARCHAR(20) | DOING（进行中）/ DONE（已完成） |
| deadline | DATETIME NULL | 完成时间（截止） |
| progress | INT | 整体进度 0-100，落库便于列表查询 |
| deleted | TINYINT | 软删除 |
| done_at | DATETIME NULL | 完成时间 |
| created_at / updated_at | DATETIME | |

### 3.4 task_member（任务成员 — 统一分配模型）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| task_id | BIGINT | |
| user_id | BIGINT | 成员 |
| weight | INT | 权重整数。个人任务 = 100；小组任务默认均分（100/n），可手动调整 |
| progress | INT | 个人进度 0-100，**审核通过后更新** |
| created_at / updated_at | DATETIME | |

- 个人任务 = 1 条记录（weight=100）
- 小组任务 = N 条记录（每个组员一条，创建时快照成员，后续小组成员变动不影响已分配任务）

### 3.5 report（汇报）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| task_member_id | BIGINT | 关联成员记录 |
| user_id | BIGINT | 汇报人 |
| content | TEXT | 汇报内容 |
| progress | INT | 本次汇报的目标进度 0-100 |
| status | VARCHAR(20) | PENDING / APPROVED / REJECTED |
| reviewer_id | BIGINT NULL | 审核人 |
| review_comment | VARCHAR(255) NULL | 审核意见（驳回时必填） |
| reviewed_at | DATETIME NULL | 审核时间 |
| created_at | DATETIME | |

### 3.6 task_attachment（任务附件）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| task_id | BIGINT | |
| file_name | VARCHAR(255) | 原始文件名 |
| file_url | VARCHAR(500) | MinIO 访问 URL |
| file_size | BIGINT | 字节 |
| uploaded_by | BIGINT | 上传人 |
| created_at | DATETIME | |

### 3.7 comment（评论/回复）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| task_id | BIGINT | |
| parent_id | BIGINT | 0 = 顶级评论；>0 = 回复该评论（一级嵌套，不允许回复回复） |
| user_id | BIGINT | |
| content | TEXT | |
| created_at | DATETIME | |

### 3.8 minio_file（通用文件记录）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | |
| object_name | VARCHAR(255) | MinIO object 名 |
| file_name | VARCHAR(255) | |
| size | BIGINT | |
| uploader_id | BIGINT | |
| created_at | DATETIME | |

通用上传接口落此表，任务附件引用其 URL；为汇报附件、头像等留扩展点。

## 4. 核心业务规则

### 4.1 任务分配

- 创建任务：分配者选择「个人」或「小组」。
  - 个人：生成 1 条 task_member（weight=100）
  - 小组：对该组**当时所有组员**各生成 1 条 task_member，权重默认均分 `100/n`；分配者创建后可通过 `PUT /tasks/{id}/weights` 调整各成员权重
- 小组任务按 `task_member` 快照，创建后小组增删成员**不影响**已分配任务。

### 4.2 汇报与审核

1. 员工（成员）对分配给自己的任务提交汇报：内容 + 目标进度 + 说明
2. 汇报状态 PENDING → 分配者（创建者）在「待我审核」列表查看
3. 审核：
   - 通过：事务内更新 `task_member.progress = report.progress`，重算任务整体进度，必要时置 DONE
   - 驳回：填审核意见，成员进度不变，汇报状态 REJECTED
4. 汇报进度必须 ≥ 当前成员进度（单调递增），否则拒绝提交

### 4.3 任务整体进度与完成判定

```
整体进度 = Σ(成员进度 × 成员权重) / Σ权重
```

- 个人任务：整体进度 = 成员进度
- 小组任务：加权平均；默认均分下等于各成员进度平均值
- 所有成员进度 = 100 → 整体进度 = 100 → 任务 DONE，记录 done_at
- 任务进度、状态由后端在审核通过时计算并落库

### 4.4 评论

- 顶级评论 + 一级回复（parent_id），平铺时间序展示
- 任务参与者（成员、创建者、本组组长、管理员）均可评论；回复时 `@用户名` 展示在渲染层

### 4.5 附件

- 创建任务时可上传多个附件 → 前端直传 MinIO（或经后端 `/files/upload`），任务详情可下载/预览
- 先做任务级附件；汇报附件为本设计外扩展点（minio_file 已留表）

## 5. 后端接口清单

统一前缀 `/api`，返回 `{code, message, data}`；`code=0` 成功。JWT 放 `Authorization: Bearer <token>`。

### 认证
- `POST /auth/login` — {username, password} → {token, user}
- `GET /auth/me` — 当前用户信息

### 用户管理（ADMIN）
- `GET /users?keyword=&role=` — 分页列表
- `POST /users` — 创建
- `PUT /users/{id}` — 修改（含角色、所属组）
- `DELETE /users/{id}` — 删除（有任务成员记录者拒绝）
- `PUT /users/{id}/password` — 重置密码

### 小组管理（LEADER 管自己的组 / ADMIN 全部）
- `GET /groups` — 列表（含组长名、成员数）
- `POST /groups` — 创建（leader_id）
- `PUT /groups/{id}` — 修改
- `DELETE /groups/{id}` — 删除（组内有未完成任务者拒绝）
- `GET /groups/{id}/members` — 成员列表
- `POST /groups/{id}/members` — 添加成员（userId）
- `DELETE /groups/{id}/members/{userId}` — 移除成员

### 任务
- `GET /tasks?type=mine_created|assigned|all&status=&keyword=` — 列表（含进度、成员数）；**可见性**：员工仅见本人参与或本组任务（所有 type 均过滤），组长/管理员可见全部
- `POST /tasks` — 创建（name, description, deadline, assignType, assigneeId, attachments[]）
- `GET /tasks/{id}` — 详情（含成员+权重+进度、附件）
- `PUT /tasks/{id}` — 修改基本信息（分配者/管理员）
- `DELETE /tasks/{id}` — 软删除（有 PENDING 汇报者拒绝）
- `PUT /tasks/{id}/weights` — 调整小组成员权重 [{userId, weight}]（分配者/管理员）

### 附件
- `POST /files/upload` — multipart 上传 → 落 MinIO + minio_file，返回 {url, id}
- `DELETE /files/{id}` — 删除

### 汇报
- `POST /tasks/{id}/reports` — 提交汇报 {content, progress}
- `GET /tasks/{id}/reports` — 任务汇报列表（成员、分配者、组长、管理员）
- `GET /reports/pending` — 待我审核列表（审核人 = 当前用户）
- `POST /reports/{id}/approve` — 通过
- `POST /reports/{id}/reject` — 驳回 {reviewComment}

### 评论
- `GET /tasks/{id}/comments` — 列表（顶级 + 回复）
- `POST /tasks/{id}/comments` — 发评论 {content}
- `POST /comments/{id}/reply` — 回复 {content}

## 6. 前端页面

布局：左侧菜单（按角色渲染）+ 顶部栏（用户名/角色/退出）。

| 页面 | 路由 | 要点 |
|---|---|---|
| 登录 | /login | 账号密码登录，存 token 到 localStorage，路由守卫 |
| 任务列表 | /tasks | Tab：我创建的 / 分配给我的 / 全部；状态筛选；进度条；创建按钮（组长/管理员）。**可见范围**：员工只能看到本人参与的或本组任务（「全部」Tab 同样过滤）；组长/管理员可见全部 |
| 创建任务 | /tasks/create | 三步：基本信息 → 分配对象（个人/小组 + 权重，默认均分）→ 附件上传 |
| 任务详情 | /tasks/:id | 信息 + 附件 + 成员进度列表（权重标注）+ 汇报区（查看/提交/审核按钮按角色）+ 评论区 |
| 待我审核 | /reports/pending | 汇报卡片列表，通过/驳回对话框 |
| 小组管理 | /groups | 组长：建组、改组成员；管理员全部可管 |
| 用户管理 | /users | 管理员：增删改、角色分配、重置密码 |

## 7. 测试策略

- 后端 JUnit 5 单测：进度加权计算、审核流转、权限校验、进度单调递增约束
- 关键接口集成测试：创建小组任务（均分/手动权重）→ 汇报 → 审核 → 整体进度 → 完成；个人任务闭环
- 前端：人工冒烟验收
- **验收闭环**：管理员建小组 → 组长建任务（设权重）→ 组员汇报 → 组长审核 → 进度变化 → 全员 100% → 任务 DONE

## 8. 种子数据

- `admin / admin123`（ADMIN）
- 组长：`leader1 / 123456`，组「研发一组」
- 组员：`zhangsan / 123456`、`lisi / 123456`、`wangwu / 123456`（均属研发一组）
- 示例任务 1-2 条（含汇报样例）
