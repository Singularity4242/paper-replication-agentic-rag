CREATE TABLE papers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    library_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(64) NOT NULL UNIQUE,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADED')),
    gmt_create TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_papers_library FOREIGN KEY (library_id) REFERENCES paper_libraries(id) ON DELETE RESTRICT,
    CONSTRAINT uk_papers_library_sha256 UNIQUE (library_id, sha256)
);

CREATE INDEX idx_papers_library_created ON papers (library_id, gmt_create DESC, id DESC);

COMMENT ON TABLE papers IS '上传的论文文件及业务信息，暂未解析或建立检索索引';
COMMENT ON COLUMN papers.library_id IS '所属论文库；非空论文库禁止删除';
COMMENT ON COLUMN papers.original_filename IS '展示用原文件名，不用于构造存储路径';
COMMENT ON COLUMN papers.storage_key IS '存储根目录下由服务生成的文件名，不对外暴露';
COMMENT ON COLUMN papers.file_size IS '实际保存的文件字节数';
COMMENT ON COLUMN papers.sha256 IS '内容摘要，同库去重';
COMMENT ON COLUMN papers.status IS 'UPLOADED 仅表示已保存，后续导入阶段扩展状态';
