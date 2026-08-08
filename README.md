# 开发任务分配系统

面向小团队的开发任务分配与汇报审核系统：支持三级角色（管理员 / 组长 / 员工）、小组与个人任务分配、
成员权重、汇报提交与审核（通过可调进度、驳回需理由）、任务评论与汇报评论/回复、附件上传（MinIO）。

## 功能概览

- **任务分配**：组长可创建小组任务（按成员权重分配，合计 100%）或个人任务（指定员工）；
  任务列表支持「全部 / 我创建的 / 分配给我的」视图与状态/关键词筛选。
- **汇报闭环**：成员提交进度汇报 → 组长在「待我审核」通过（可调整最终进度）或驳回（必填理由）；
  汇报可撤回/编辑/重新提交；组员全部 100% 后任务自动标记「已完成」。
- **可见性**：待审核汇报仅审核人与提交人可见，其他人仅见已通过的汇报（防止互相干扰）。
- **评论协作**：任务级评论与汇报级评论，支持回复。
- **管理**：管理员维护用户（建/改/重置密码/删除，含完整性保护）；管理员与组长管理小组及成员。
- **附件**：任务附件经 MinIO 存取，仅任务可管理方可下载。

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17 · Spring Boot 3.2.5 · MyBatis-Plus 3.5.7 · Spring Security (JWT) |
| 数据库 | MySQL 8 |
| 对象存储 | MinIO（bucket `task-attachments`） |
| 前端 | Vue 3 · Element Plus · Pinia · Vue Router · Vite |

## 目录结构

```
task/
├── backend/          # Spring Boot 后端
│   └── src/main/resources/db/   # schema.sql 与增量迁移脚本
├── frontend/         # Vue 3 前端
└── docs/
    ├── superpowers/plans/       # 开发计划
    └── superpowers/verification/ # 各任务验收记录
```

## 启动方式

### 前置

- JDK 17、Maven、Node.js（18+）、Docker（MySQL 与 MinIO）

### 1. 初始化数据库与 MinIO

```bash
docker run -d --name mysql-dev -e MYSQL_ROOT_PASSWORD=root123456 -p 3306:3306 mysql:8
docker run -d --name minio-dev -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=minioadmin minio/minio server /data --console-address ":9001"
```

创建数据库并应用迁移脚本（`backend/src/main/resources/db/` 下的 `schema.sql` 与按日期命名的增量迁移，
按文件名顺序执行一次）：

```bash
mysql -uroot -proot123456 -e "CREATE DATABASE IF NOT EXISTS task_assign DEFAULT CHARACTER SET utf8mb4"
mysql -uroot -proot123456 task_assign < backend/src/main/resources/db/schema.sql
mysql -uroot -proot123456 task_assign < backend/src/main/resources/db/migration-*.sql
```

> 开发库默认账号见下表；MinIO 需在控制台（9001）创建 bucket `task-attachments`。

### 2. 启动后端（默认 8080）

```bash
cd backend
mvn spring-boot:run
```

### 3. 启动前端（默认 5173）

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 http://localhost:5173。

### 测试

```bash
cd backend && mvn test          # 后端集成测试（真实 MySQL）
cd frontend && npm run build    # 前端构建
```

## 默认账号

| 账号 | 密码 | 角色 | 说明 |
|------|------|------|------|
| `admin` | `admin123` | 管理员 | 用户管理、全部权限 |
| `leader1` | `123456` | 组长 | 创建任务、审核、小组管理 |
| `zhangsan` | `123456` | 员工 | 任务汇报、评论 |
| `lisi` | `123456` | 员工 | 开发期种子，密码为开发约定 |
| `wangwu` | `123456` | 员工 | 同上 |

## 权限矩阵（简）

| 操作 | 管理员 | 组长 | 员工 |
|------|:--:|:--:|:--:|
| 用户管理（建/改/重置/删） | ✓ | – | – |
| 小组管理（建组/成员） | ✓ | ✓（含自己为组长的小组） | – |
| 创建任务（小组/个人） | ✓ | ✓ | – |
| 提交/撤回/编辑汇报 | 成员 | 成员 | 成员 |
| 审核汇报（待我审核） | ✓ | ✓（自己创建的任务） | – |
| 任务/汇报评论 | 成员 | 成员 | 成员 |
