ALTER TABLE documents DROP CONSTRAINT ck_documents_index_status;
ALTER TABLE documents ADD CONSTRAINT ck_documents_index_status CHECK
    (index_status IN ('NOT_REQUESTED', 'QUEUED', 'PROCESSING', 'INDEXED', 'UNSUPPORTED', 'FAILED'));
ALTER TABLE documents ALTER COLUMN index_status SET DEFAULT 'QUEUED';
ALTER TABLE documents ADD COLUMN rag_document_id VARCHAR(255);
ALTER TABLE documents ADD COLUMN indexed_at TIMESTAMPTZ;

CREATE TABLE document_ingestion_tasks (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id BIGINT NOT NULL UNIQUE REFERENCES documents(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'PROCESSING', 'INDEXED', 'UNSUPPORTED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_token UUID,
    lease_until TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_message VARCHAR(1000),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    gmt_create TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ingestion_queue ON document_ingestion_tasks (status, next_attempt_at);
CREATE INDEX idx_ingestion_lease ON document_ingestion_tasks (lease_until) WHERE status = 'PROCESSING';
COMMENT ON TABLE document_ingestion_tasks IS '每个资料一条可恢复的索引任务；失败重试复用任务';
COMMENT ON COLUMN document_ingestion_tasks.execution_token IS '执行批次标识，拒绝过期执行结果覆盖新状态';
COMMENT ON COLUMN document_ingestion_tasks.lease_until IS '执行占用截止时间，过期后可恢复';

-- 历史文件自动补建任务；迁移仅写数据库，不调用 Python 或模型。
INSERT INTO document_ingestion_tasks (document_id)
SELECT id FROM documents WHERE index_status = 'NOT_REQUESTED';
UPDATE documents SET index_status = 'QUEUED' WHERE index_status = 'NOT_REQUESTED';

COMMENT ON COLUMN documents.index_status IS '资料索引状态：等待、处理中、就绪、不支持或失败；与原文件保存状态独立';
COMMENT ON COLUMN documents.rag_document_id IS '索引成功后关联的 LanceDB 文档标识';
COMMENT ON COLUMN documents.indexed_at IS '最近一次成功完成索引的时间';
