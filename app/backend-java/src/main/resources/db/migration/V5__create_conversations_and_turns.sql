CREATE TABLE conversations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    library_id BIGINT NOT NULL REFERENCES paper_libraries(id) ON DELETE RESTRICT,
    title VARCHAR(200) NOT NULL,
    document_scope JSONB NOT NULL CHECK (jsonb_typeof(document_scope) = 'array' AND jsonb_array_length(document_scope) > 0),
    checkpoint JSONB,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    gmt_create TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_conversations_library_id ON conversations(library_id, id DESC);

-- A turn stores one user question and its assistant outcome together.
CREATE TABLE conversation_turns (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    question TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    answer JSONB,
    error_code VARCHAR(64),
    error_message VARCHAR(300),
    execution_token UUID NOT NULL,
    base_revision BIGINT NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    gmt_create TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    UNIQUE (conversation_id, request_id),
    CHECK ((status = 'RUNNING' AND finished_at IS NULL AND answer IS NULL)
        OR (status = 'COMPLETED' AND finished_at IS NOT NULL AND answer IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND finished_at IS NOT NULL AND answer IS NULL AND error_code IS NOT NULL))
);
CREATE UNIQUE INDEX uk_conversation_running ON conversation_turns(conversation_id) WHERE status = 'RUNNING';
CREATE INDEX idx_turns_conversation_id ON conversation_turns(conversation_id, id);
CREATE INDEX idx_turns_expired ON conversation_turns(lease_until) WHERE status = 'RUNNING';
COMMENT ON COLUMN conversations.document_scope IS '创建会话时固定的业务文档与 RAG 文档映射';
COMMENT ON COLUMN conversations.checkpoint IS '仅供服务端恢复已完成轮次的 Python 消息与 Agent 状态，不对前端暴露';
COMMENT ON TABLE conversation_turns IS '用户可见的逐轮问答历史；失败轮次不写入 Agent checkpoint';
