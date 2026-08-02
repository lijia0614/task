-- ============================================================================
-- 增量迁移 2026-08-02: 附件所有权关联 + 汇报撤回审计表
-- 用途: task_attachment.minio_file_id 回填 + 唯一索引; report 状态注释;
--       report_history 审计表
-- 注意: 幂等性由执行方控制（执行前核对列/表是否存在），本脚本不含 IF NOT EXISTS
--       守护；执行前先运行只读预检查（重复绑定 / 无法回填附件）。
-- ============================================================================

-- 1. 增加可空关联列
ALTER TABLE task_attachment ADD COLUMN minio_file_id BIGINT NULL AFTER task_id;

-- 2. 回填历史关联（file_url 末尾 object_name 精确匹配）
UPDATE task_attachment ta
JOIN minio_file mf
  ON ta.file_url = CONCAT('http://127.0.0.1:9000/task-attachments/', mf.object_name)
SET ta.minio_file_id = mf.id
WHERE ta.minio_file_id IS NULL;

-- 3. 唯一索引: 一个上传文件最多绑定到一个任务
ALTER TABLE task_attachment
  ADD UNIQUE KEY uk_attachment_minio_file (minio_file_id);

-- 4. report 状态注释补充 WITHDRAWN（不影响数据）
ALTER TABLE report
  MODIFY status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/APPROVED/REJECTED/WITHDRAWN';

-- 5. 汇报状态变化审计表
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
