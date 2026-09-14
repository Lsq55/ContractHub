-- 第四阶段：确认凭据指纹、签署件作废、补齐查询索引。
-- 全部语句在 H2(PostgreSQL 兼容模式) 与 PostgreSQL 16 上均可执行。
ALTER TABLE review_receipts ADD COLUMN template_hash VARCHAR(64);
ALTER TABLE review_receipts ADD COLUMN schema_hash VARCHAR(64);
ALTER TABLE review_receipts ADD COLUMN render_profile_id VARCHAR(100);
ALTER TABLE signed_attachments ADD COLUMN voided_at TIMESTAMP;
ALTER TABLE signed_attachments ADD COLUMN voided_by VARCHAR(36);
ALTER TABLE signed_attachments ADD COLUMN void_reason TEXT;
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
CREATE INDEX IF NOT EXISTS idx_versions_template ON template_versions(template_id,version_no);
CREATE INDEX IF NOT EXISTS idx_revisions_contract ON contract_revisions(contract_id,revision_no);
CREATE INDEX IF NOT EXISTS idx_jobs_revision ON generation_jobs(revision_id);
CREATE INDEX IF NOT EXISTS idx_jobs_template_version ON generation_jobs(template_version_id);
CREATE INDEX IF NOT EXISTS idx_receipts_revision ON review_receipts(revision_id);
CREATE INDEX IF NOT EXISTS idx_attachments_contract ON signed_attachments(contract_id,created_at);
CREATE INDEX IF NOT EXISTS idx_files_created ON files(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_logs(actor_id,created_at);
