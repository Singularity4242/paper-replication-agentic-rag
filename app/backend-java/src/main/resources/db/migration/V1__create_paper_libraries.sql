-- Flyway V1：仅定义论文库业务表，不创建 PostgreSQL 实例、数据库或用户。
-- 在持久环境执行成功后保持本文件不变，后续结构变化新增 V2、V3 等脚本。
CREATE TABLE paper_libraries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(2000),
    gmt_create TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_paper_libraries_name_not_blank CHECK (name ~ '[^[:space:]]')
);

COMMENT ON TABLE paper_libraries IS '论文库';
COMMENT ON COLUMN paper_libraries.id IS '主键';
COMMENT ON COLUMN paper_libraries.name IS '论文库名称，允许重名';
COMMENT ON COLUMN paper_libraries.description IS '论文库描述，可为空';
COMMENT ON COLUMN paper_libraries.gmt_create IS '创建时间';
COMMENT ON COLUMN paper_libraries.gmt_modified IS '修改时间；后续 UPDATE 语句需显式更新';
