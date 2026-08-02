# Task Deletion, Attachment Ownership, and Report Withdrawal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce creator-only task deletion, uploader-only attachment lifecycle with MinIO cleanup, and editable withdrawal/resubmission of pending reports with audit history.

**Architecture:** Add explicit `task_attachment.minio_file_id` ownership linkage and a `report_history` audit table through an additive migration. Serialize attachment bind/delete and report state transitions with `SELECT ... FOR UPDATE`; wrap MinIO deletion behind one focused service shared by file and task deletion. Preserve the current uncommitted Task 13 frontend work and integrate it only after backend contracts pass.

**Tech Stack:** Java 17, Spring Boot 3.2, MyBatis-Plus 3.5, MySQL 8, MinIO SDK 8.5, JUnit 5/MockMvc/Mockito, Vue 3, Element Plus, Axios, Vite.

---

## Execution Safety

- Do not clear, reset, or bulk-delete the development database.
- Do not run `backend/scripts/smoke.sh`.
- Do not restart or stop the existing backend on port 8080 or frontend on port 5173.
- MinIO behavior in tests must use `@MockBean MinioClient`; never delete a real object during tests.
- Every integration test tracks and physically removes only its own rows in dependency order.
- Before applying the additive migration, run read-only preflight queries for duplicate attachment bindings and unmatched historical URLs. Stop and report if a uniqueness conflict exists.
- The existing uncommitted Task 13 frontend files must not be staged in backend commits.

### Task 1: Additive Schema and Persistence Model

**Files:**
- Create: `backend/src/main/resources/db/migration-2026-08-02-ownership-withdrawal.sql`
- Modify: `backend/src/main/resources/db/schema.sql`
- Modify: `backend/src/main/java/com/task/entity/TaskAttachment.java`
- Modify: `backend/src/main/java/com/task/enums/ReportStatus.java`
- Create: `backend/src/main/java/com/task/entity/ReportHistory.java`
- Create: `backend/src/main/java/com/task/mapper/ReportHistoryMapper.java`

- [ ] **Step 1: Run read-only migration preflight**

Run SQL that reports, without changing data:

```sql
SELECT mf.id, COUNT(*) AS matches
FROM minio_file mf
JOIN task_attachment ta
  ON ta.file_url = CONCAT('http://127.0.0.1:9000/task-attachments/', mf.object_name)
GROUP BY mf.id
HAVING COUNT(*) > 1;

SELECT ta.id, ta.file_url
FROM task_attachment ta
LEFT JOIN minio_file mf
  ON ta.file_url = CONCAT('http://127.0.0.1:9000/task-attachments/', mf.object_name)
WHERE mf.id IS NULL;
```

Expected: no duplicated `minio_file` matches. Unmatched rows may exist and must be reported; they remain with `minio_file_id = NULL` and make their task undeletable until repaired. Stop before applying the migration if duplicate matches would violate the unique index.

Then write the additive migration, but do not execute it yet:

The migration must add and backfill the relationship, then create audit storage:

```sql
ALTER TABLE task_attachment ADD COLUMN minio_file_id BIGINT NULL AFTER task_id;

UPDATE task_attachment ta
JOIN minio_file mf
  ON ta.file_url = CONCAT('http://127.0.0.1:9000/task-attachments/', mf.object_name)
SET ta.minio_file_id = mf.id
WHERE ta.minio_file_id IS NULL;

ALTER TABLE task_attachment
  ADD UNIQUE KEY uk_attachment_minio_file (minio_file_id);

ALTER TABLE report
  MODIFY status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/APPROVED/REJECTED/WITHDRAWN';

CREATE TABLE IF NOT EXISTS report_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  report_id BIGINT NOT NULL,
  action VARCHAR(20) NOT NULL,
  content TEXT NOT NULL,
  progress INT NOT NULL,
  actor_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_report_history_report (report_id)
) ENGINE=InnoDB;
```

Review the exact preflight result and migration diff before applying it. Record existing columns, indexes, and tables so the migration is not run twice.

- [ ] **Step 2: Update fresh-install schema and Java model**

Add `minio_file_id` and its unique key to `schema.sql`, add the `report_history` table, add `WITHDRAWN` to `ReportStatus`, add `Long minioFileId` to `TaskAttachment`, and create `ReportHistory` plus a plain `BaseMapper<ReportHistory>`.

- [ ] **Step 3: Compile and commit**

Run:

```bash
cd backend && mvn -q -DskipTests compile
cd .. && git diff --check
```

Expected: compile succeeds and diff check is clean.

Commit only Task 1 files:

```bash
git commit -m "feat(backend): add attachment linkage and report history schema"
```

After Task 1 is committed and before Task 2's focused tests, apply the reviewed additive migration exactly once. These tests connect to the current MySQL schema, so do not defer migration application until Task 5. Do not run `schema.sql`.

### Task 2: Uploader-Only File Deletion With MinIO Cleanup

**Files:**
- Create: `backend/src/main/java/com/task/service/MinioObjectService.java`
- Modify: `backend/src/main/java/com/task/service/FileService.java`
- Modify: `backend/src/main/java/com/task/service/impl/FileServiceImpl.java`
- Modify: `backend/src/main/java/com/task/mapper/MinioFileMapper.java`
- Modify: `backend/src/main/java/com/task/mapper/TaskAttachmentMapper.java`
- Test: `backend/src/test/java/com/task/controller/FileOwnershipTest.java`

- [ ] **Step 1: Write failing ownership and cleanup tests**

Use `@SpringBootTest`, `@AutoConfigureMockMvc`, and `@MockBean MinioClient`. Insert test-owned `minio_file` rows directly and clean only those rows. Cover:

```java
@Test void uploaderDeletesOwnUnboundFileAndMinioObject() { /* code=0; verify removeObject bucket/object */ }
@Test void otherUserCannotDeleteFile() { /* code=403; row remains; never removeObject */ }
@Test void adminCannotDeleteAnotherUsersFile() { /* code=403 */ }
@Test void boundFileCannotBeDeletedDirectly() { /* code=400; row and attachment remain */ }
@Test void minioFailureKeepsDatabaseRow() { /* code=400; row remains */ }
```

- [ ] **Step 2: Run the focused test and confirm RED**

```bash
cd backend && mvn test -Dtest=FileOwnershipTest
```

Expected: failures because deletion neither checks ownership/binding nor calls MinIO.

- [ ] **Step 3: Add locked mapper reads**

Add mapper methods using explicit SQL. Keep lock acquisition deterministic: when multiple attachments are involved, lock their `minio_file` rows in ascending attachment/file ID order to reduce deadlock risk.

```java
@Select("SELECT * FROM minio_file WHERE id = #{id} FOR UPDATE")
MinioFile selectByIdForUpdate(Long id);

@Select("SELECT * FROM task_attachment WHERE minio_file_id = #{fileId} LIMIT 1")
TaskAttachment selectByMinioFileId(Long fileId);
```

- [ ] **Step 4: Implement one MinIO deletion boundary**

`MinioObjectService.delete(String objectName)` must call:

```java
minioClient.removeObject(RemoveObjectArgs.builder()
    .bucket(bucket)
    .object(objectName)
    .build());
```

Treat a missing object as success; translate other SDK failures to `BusinessException("附件删除失败")` without deleting the database row.

- [ ] **Step 5: Enforce delete contract transactionally**

`FileServiceImpl.delete` must be `@Transactional`, lock the file row, check `f.uploaderId == UserContext.get().id`, reject bound files, delete MinIO first, and only then delete `minio_file`.

- [ ] **Step 6: Run tests and commit**

```bash
cd backend && mvn test -Dtest=FileOwnershipTest,FileControllerTest
cd .. && git diff --check
git commit -m "feat(backend): enforce attachment ownership and cleanup"
```

### Task 3: Safe Attachment Binding and Creator-Only Task Deletion

**Files:**
- Modify: `backend/src/main/java/com/task/service/impl/TaskServiceImpl.java`
- Modify: `backend/src/main/java/com/task/mapper/MinioFileMapper.java`
- Modify: `backend/src/main/java/com/task/mapper/TaskAttachmentMapper.java`
- Test: `backend/src/test/java/com/task/controller/TaskDeletionOwnershipTest.java`
- Test: `backend/src/test/java/com/task/controller/TaskAttachmentBindingTest.java`

- [ ] **Step 1: Write failing task-deletion tests**

Cover creator success, admin/other-user 403, pending-report rejection, all attached MinIO objects removed, attachment/minio rows removed, and MinIO failure leaving task/database rows unchanged. Test rows and mock interactions must be isolated.

- [ ] **Step 2: Write failing binding tests**

Cover missing ID, another uploader's ID, already-bound ID, duplicate IDs in one request, and valid owned IDs. Invalid attachment input must fail the whole task creation with no task/member/attachment inserted.

- [ ] **Step 3: Confirm focused tests fail**

```bash
cd backend && mvn test -Dtest=TaskDeletionOwnershipTest,TaskAttachmentBindingTest
```

- [ ] **Step 4: Validate and lock attachments before task insert**

Before `taskMapper.insert`, reject duplicate `attachmentIds`, lock every `minio_file`, require the current creator as uploader, and require no existing `task_attachment` row. Store `minioFileId` and the real uploader ID on each attachment. Do not silently skip invalid IDs.

- [ ] **Step 5: Restrict task deletion and clean attachments**

Do not use the existing admin-capable `canManage` check for deletion. Require exact creator equality. After the pending-report check, reject legacy attachments with null/missing linkage or mismatched uploader; remove all MinIO objects; delete attachment and minio rows; then logically delete the task.

- [ ] **Step 6: Run focused and regression tests, then commit**

```bash
cd backend && mvn test -Dtest=TaskDeletionOwnershipTest,TaskAttachmentBindingTest,WeightValidationTest
cd .. && git diff --check
git commit -m "feat(backend): restrict task deletion and bind owned attachments"
```

### Task 4: Withdraw, Edit, and Resubmit Reports

**Files:**
- Modify: `backend/src/main/java/com/task/mapper/TaskMemberMapper.java`
- Modify: `backend/src/main/java/com/task/mapper/ReportMapper.java`
- Modify: `backend/src/main/java/com/task/service/ReportService.java`
- Modify: `backend/src/main/java/com/task/service/impl/ReportServiceImpl.java`
- Modify: `backend/src/main/java/com/task/controller/ReportController.java`
- Modify: `backend/src/main/java/com/task/vo/ReportVO.java` only if needed for frontend ownership/state rendering
- Test: `backend/src/test/java/com/task/controller/ReportWithdrawalTest.java`
- Test: `backend/src/test/java/com/task/controller/ReportPendingUniquenessTest.java`

- [ ] **Step 1: Write failing state-machine tests**

Cover owner-only withdrawal, pending-only withdrawal, withdrawn-only edit/resubmit, progress validation, reviewer-field clearing, withdrawn visibility, pending-list removal, preserved report ID, and history actions/snapshots.

- [ ] **Step 2: Write failing duplicate-pending and stale-state tests**

Cover a second submit while one pending exists, resubmit while another pending exists, approve/reject after withdrawal, and withdrawal after approve/reject. Expected stale transition message: `汇报状态已变化，请刷新`.

- [ ] **Step 3: Confirm tests fail**

```bash
cd backend && mvn test -Dtest=ReportWithdrawalTest,ReportPendingUniquenessTest
```

- [ ] **Step 4: Add row locks and history helper**

Add `SELECT ... FOR UPDATE` methods for report and task member rows. Add a private helper that inserts `ReportHistory` after each successful action. `APPROVED` history records actual final progress; other actions record requested report progress.

- [ ] **Step 5: Implement transitions and endpoints**

Add service/controller methods. Also update `CommentServiceImpl` so comments associated with `WITHDRAWN` reports are hidden from reviewers and other users while the report is withdrawn.

```java
void withdraw(Long reportId);
void update(Long reportId, ReportRequest req);
void resubmit(Long reportId);
```

Endpoints are `POST /reports/{id}/withdraw`, `PUT /reports/{id}`, and `POST /reports/{id}/resubmit`. Validate ownership before reporting state errors. Clear prior review fields on resubmit. Filter `WITHDRAWN` so only the report owner sees it.

- [ ] **Step 6: Prevent multiple pending reports**

In both new submit and resubmit transactions, lock the task-member row and reject if any other pending report exists for that member. Keep the existing progress rules.

- [ ] **Step 7: Run report and comment visibility regressions, then commit**

```bash
cd backend && mvn test -Dtest=ReportWithdrawalTest,ReportPendingUniquenessTest,ReportReviewAuthTest,CommentControllerTest
cd .. && git diff --check
git commit -m "feat(backend): support report withdrawal and resubmission"
```

### Task 5: Verify Applied Migration and Backend

**Files:**
- No code changes unless a verified defect is found.

- [ ] **Step 1: Verify the already-applied migration**

Report duplicate matches, unmatched attachment rows, existing columns/index/table, and row counts. Do not apply the migration if a duplicate binding would break the unique index.

Confirm the migration objects, indexes, backfill counts, and unmatched legacy attachment IDs. Do not apply the migration again and do not run `schema.sql`, because it contains broader database setup and is not the incremental operation.

- [ ] **Step 2: Run the complete backend suite**

```bash
cd backend && mvn test
```

Expected: all tests pass. Confirm test cleanup did not increase counts of test-prefixed tasks, groups, users, reports, comments, attachments, or history rows.

- [ ] **Step 3: Stop at backend checkpoint**

Do not restart port 8080. Report migration result, test totals, unmatched legacy attachment IDs, and git status for review.

### Task 6: Finish Current Task 13 Frontend Against Safe File Contract

**Files:**
- Preserve/finish: `frontend/src/api/file.js`
- Preserve/finish: `frontend/src/api/group.js`
- Preserve/finish: `frontend/src/api/user.js`
- Preserve/finish: `frontend/src/views/TaskCreate.vue`
- Preserve/finish: `frontend/src/router/index.js`
- Modify: `frontend/src/api/task.js` only if the new report endpoints are added now; otherwise defer them to Task 14.

- [ ] **Step 1: Rebase the uncommitted logic on the approved backend contract**

Keep request-sequence protection, group-loading state, uploader-only `deleteFile`, and direct-route authorization. For an upload removed while in flight, if upload later returns an ID, call `deleteFile(id)` immediately and do not add it to `attachmentIds`.

- [ ] **Step 2: Make cleanup retryable**

Removing or cancelling uploaded temporary files must keep failed cleanup visible and retryable. Successful task creation marks attachments as bound and skips page cleanup.

- [ ] **Step 3: Run non-writing browser verification**

Use existing Python Playwright with request interception. Mock `GET /api/users`, `GET /api/groups`, member responses, upload, delete, and create calls. Verify individual/group payloads, default weights, empty group blocking, stale member response rejection, upload removal cleanup, cancel cleanup failure, create failure form preservation, role redirect, and desktop/390px layout. Do not call real POST/DELETE/upload endpoints.

- [ ] **Step 4: Build, diff-check, and commit Task 13**

```bash
cd frontend && npm run build
cd .. && git diff --check
git commit -m "feat(frontend): add safe three-step task creation"
```

Commit only the five Task 13 frontend files after independent review.

### Task 7: Full Checkpoint

- [ ] **Step 1: Verify repository state and history**

Run `git status --short --branch`, `git log --oneline -10`, and `git diff --check`.

- [ ] **Step 2: Report remaining UI work**

Report withdrawal UI remains part of the subsequent task-detail implementation unless implemented and independently verified in the same checkpoint. Do not start Task 14 without reviewer approval.
