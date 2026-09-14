CREATE TABLE system_guard (id INTEGER PRIMARY KEY);
INSERT INTO system_guard(id) VALUES (1);
CREATE TABLE users (
 id VARCHAR(36) PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE, display_name VARCHAR(100) NOT NULL,
 password_hash VARCHAR(255) NOT NULL, role VARCHAR(32) NOT NULL CHECK (role IN ('ADMIN','CONTRACT_MAINTAINER','USER')),
 enabled BOOLEAN NOT NULL DEFAULT TRUE, must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
 session_epoch INTEGER NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE sessions (
 id VARCHAR(64) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES users(id), session_epoch INTEGER NOT NULL,
 csrf_token VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at TIMESTAMP NOT NULL, last_seen_at TIMESTAMP NOT NULL
);
CREATE TABLE files (
 id VARCHAR(36) PRIMARY KEY, storage_key VARCHAR(255) NOT NULL UNIQUE, original_name VARCHAR(255) NOT NULL,
 mime_type VARCHAR(120) NOT NULL, byte_size BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL,
 purpose VARCHAR(40) NOT NULL, created_by VARCHAR(36) REFERENCES users(id), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE templates (
 id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) NOT NULL UNIQUE, name VARCHAR(150) NOT NULL, category VARCHAR(60) NOT NULL,
 description TEXT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','DISABLED')),
 current_version_id VARCHAR(36), created_by VARCHAR(36) NOT NULL REFERENCES users(id), lock_version INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted_at TIMESTAMP, deleted_by VARCHAR(36), delete_reason TEXT
);
CREATE TABLE template_versions (
 id VARCHAR(36) PRIMARY KEY, template_id VARCHAR(36) NOT NULL REFERENCES templates(id), version_no INTEGER NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED')), docx_file_id VARCHAR(36) REFERENCES files(id),
 source_pdf_file_id VARCHAR(36) REFERENCES files(id), field_schema TEXT NOT NULL, schema_hash VARCHAR(64) NOT NULL,
 change_note TEXT NOT NULL, migration_map TEXT NOT NULL DEFAULT '{}', lock_version INTEGER NOT NULL DEFAULT 0,
 test_job_id VARCHAR(36), reviewed_fingerprint VARCHAR(64), reviewed_by VARCHAR(36), reviewed_at TIMESTAMP,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, published_at TIMESTAMP,
 deleted_at TIMESTAMP, deleted_by VARCHAR(36), delete_reason TEXT,
 UNIQUE(template_id,version_no), UNIQUE(template_id,id)
);
ALTER TABLE templates ADD CONSTRAINT fk_current_template FOREIGN KEY(id,current_version_id) REFERENCES template_versions(template_id,id);
CREATE SEQUENCE contract_number_seq START WITH 1;
CREATE TABLE contracts (
 id VARCHAR(36) PRIMARY KEY, contract_no VARCHAR(40) NOT NULL UNIQUE, title VARCHAR(150) NOT NULL,
 owner_id VARCHAR(36) NOT NULL REFERENCES users(id), template_id VARCHAR(36) NOT NULL REFERENCES templates(id),
 template_version_id VARCHAR(36) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK(status IN ('DRAFT','FINALIZED','SIGNED','VOID')),
 form_data TEXT NOT NULL, lock_version INTEGER NOT NULL DEFAULT 0, current_revision_id VARCHAR(36), finalized_revision_id VARCHAR(36),
 parent_contract_id VARCHAR(36) REFERENCES contracts(id), void_reason TEXT, finalized_at TIMESTAMP,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 deleted_at TIMESTAMP, deleted_by VARCHAR(36), delete_reason TEXT,
 FOREIGN KEY(template_id,template_version_id) REFERENCES template_versions(template_id,id)
);
CREATE TABLE contract_revisions (
 id VARCHAR(36) PRIMARY KEY, contract_id VARCHAR(36) NOT NULL REFERENCES contracts(id), revision_no INTEGER NOT NULL,
 template_version_id VARCHAR(36) NOT NULL REFERENCES template_versions(id), data_snapshot TEXT NOT NULL,
 draft_lock_version INTEGER NOT NULL, template_hash VARCHAR(64) NOT NULL, schema_hash VARCHAR(64) NOT NULL,
 render_profile_id VARCHAR(100) NOT NULL, created_by VARCHAR(36) NOT NULL REFERENCES users(id), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(contract_id,revision_no), UNIQUE(contract_id,id)
);
ALTER TABLE contracts ADD CONSTRAINT fk_current_revision FOREIGN KEY(id,current_revision_id) REFERENCES contract_revisions(contract_id,id);
ALTER TABLE contracts ADD CONSTRAINT fk_finalized_revision FOREIGN KEY(id,finalized_revision_id) REFERENCES contract_revisions(contract_id,id);
CREATE TABLE generation_jobs (
 id VARCHAR(36) PRIMARY KEY, revision_id VARCHAR(36) REFERENCES contract_revisions(id), template_version_id VARCHAR(36) REFERENCES template_versions(id),
 created_by VARCHAR(36) NOT NULL REFERENCES users(id), actor_epoch INTEGER NOT NULL,
 data_snapshot TEXT NOT NULL, fingerprint VARCHAR(64) NOT NULL, dedupe_key VARCHAR(160) NOT NULL UNIQUE,
 state VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
 attempt INTEGER NOT NULL DEFAULT 0, lease_token VARCHAR(36), lease_until TIMESTAMP, heartbeat_at TIMESTAMP,
 docx_file_id VARCHAR(36) REFERENCES files(id), pdf_file_id VARCHAR(36) REFERENCES files(id), error_code VARCHAR(100),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, finished_at TIMESTAMP
);
CREATE TABLE review_receipts (
 id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES users(id), revision_id VARCHAR(36) NOT NULL REFERENCES contract_revisions(id),
 pdf_sha256 VARCHAR(64) NOT NULL, reviewed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, expires_at TIMESTAMP NOT NULL
);
CREATE TABLE preview_views (
 user_id VARCHAR(36) NOT NULL REFERENCES users(id), job_id VARCHAR(36) NOT NULL REFERENCES generation_jobs(id),
 pdf_sha256 VARCHAR(64) NOT NULL, viewed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(user_id,job_id)
);
CREATE TABLE signed_attachments (
 id VARCHAR(36) PRIMARY KEY, contract_id VARCHAR(36) NOT NULL REFERENCES contracts(id), finalized_revision_id VARCHAR(36) NOT NULL,
 file_id VARCHAR(36) NOT NULL REFERENCES files(id), signed_on DATE NOT NULL, note TEXT NOT NULL,
 supersedes_id VARCHAR(36) REFERENCES signed_attachments(id), created_by VARCHAR(36) NOT NULL REFERENCES users(id),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(contract_id,finalized_revision_id) REFERENCES contract_revisions(contract_id,id)
);
CREATE TABLE audit_logs (
 id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(36), action VARCHAR(80) NOT NULL, object_type VARCHAR(40) NOT NULL,
 object_id VARCHAR(64), request_id VARCHAR(36) NOT NULL, detail TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE idempotency_keys (
 actor_id VARCHAR(36) NOT NULL, scope VARCHAR(160) NOT NULL, key_value VARCHAR(100) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, response_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(actor_id,scope,key_value)
);
CREATE TABLE login_attempts (bucket VARCHAR(160) PRIMARY KEY, failures INTEGER NOT NULL, window_start TIMESTAMP NOT NULL);
CREATE INDEX idx_contract_owner ON contracts(owner_id,updated_at);
CREATE INDEX idx_contract_status ON contracts(status,updated_at);
CREATE INDEX idx_job_queue ON generation_jobs(state,lease_until);
CREATE INDEX idx_audit_object ON audit_logs(object_type,object_id,created_at);
