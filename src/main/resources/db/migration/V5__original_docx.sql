-- 第五阶段：保留下划线空白自动识别前的"原始上传件"，便于追溯与重新规范化。
ALTER TABLE template_versions ADD COLUMN original_docx_file_id VARCHAR(36) REFERENCES files(id);
