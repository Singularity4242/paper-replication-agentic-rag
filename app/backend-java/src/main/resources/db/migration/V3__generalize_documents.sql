-- 保留原论文文件的 ID、内容摘要、存储位置和库归属；不重建业务数据。
ALTER TABLE papers RENAME TO documents;
ALTER TABLE documents RENAME CONSTRAINT papers_pkey TO documents_pkey;
ALTER TABLE documents RENAME CONSTRAINT papers_storage_key_key TO documents_storage_key_key;
ALTER TABLE documents RENAME CONSTRAINT papers_file_size_check TO documents_file_size_check;
ALTER TABLE documents RENAME CONSTRAINT papers_sha256_check TO documents_sha256_check;
ALTER TABLE documents RENAME CONSTRAINT papers_status_check TO documents_status_check;
ALTER TABLE documents RENAME CONSTRAINT fk_papers_library TO fk_documents_library;
ALTER TABLE documents RENAME CONSTRAINT uk_papers_library_sha256 TO uk_documents_library_sha256;
ALTER INDEX idx_papers_library_created RENAME TO idx_documents_library_created;
ALTER SEQUENCE papers_id_seq RENAME TO documents_id_seq;

ALTER TABLE documents ALTER COLUMN storage_key TYPE VARCHAR(255);
ALTER TABLE documents ADD COLUMN index_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED'
    CONSTRAINT ck_documents_index_status CHECK (index_status IN ('NOT_REQUESTED'));

COMMENT ON TABLE documents IS '资料库中的通用文件；文件存储与 RAG 解析、索引能力独立';
COMMENT ON COLUMN documents.library_id IS '所属论文库；包含任何资料的库均禁止删除';
COMMENT ON COLUMN documents.status IS 'UPLOADED 仅表示原文件和业务记录已保存';
COMMENT ON COLUMN documents.index_status IS 'NOT_REQUESTED 表示尚未请求索引，不代表支持或不支持解析';
