package com.example.paperassistant.dao.impl;

import com.example.paperassistant.dao.IngestionTaskDAO;
import com.example.paperassistant.model.dataobject.IngestionTaskDO;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcIngestionTaskDAO implements IngestionTaskDAO {
    private static final RowMapper<IngestionTaskDO> MAPPER = (rs, row) -> new IngestionTaskDO(
            rs.getLong("id"), rs.getLong("document_id"), rs.getString("status"), rs.getInt("attempt_count"),
            rs.getObject("execution_token", UUID.class), rs.getObject("next_attempt_at", OffsetDateTime.class),
            rs.getObject("lease_until", OffsetDateTime.class), rs.getString("error_code"), rs.getString("error_message"),
            rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class));
    private final JdbcClient jdbc;

    public JdbcIngestionTaskDAO(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public void enqueue(long documentId) {
        jdbc.sql("INSERT INTO document_ingestion_tasks (document_id) VALUES (:id)")
                .param("id", documentId).update();
    }

    @Override
    public Optional<IngestionTaskDO> findByDocumentId(long documentId) {
        return jdbc.sql("SELECT * FROM document_ingestion_tasks WHERE document_id = :id")
                .param("id", documentId).query(MAPPER).optional();
    }

    @Override
    public Optional<IngestionTaskDO> claim(int maxAttempts, long leaseSeconds, UUID token) {
        return jdbc.sql("""
                WITH candidate AS (
                    SELECT id FROM document_ingestion_tasks
                    WHERE attempt_count < :max AND (
                        (status = 'QUEUED' AND next_attempt_at <= CURRENT_TIMESTAMP)
                        OR (status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP))
                    ORDER BY next_attempt_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE document_ingestion_tasks t SET status = 'PROCESSING',
                    attempt_count = attempt_count + 1, execution_token = :token,
                    lease_until = CURRENT_TIMESTAMP + :lease * INTERVAL '1 second',
                    started_at = CURRENT_TIMESTAMP, finished_at = NULL,
                    error_code = NULL, error_message = NULL, gmt_modified = CURRENT_TIMESTAMP
                FROM candidate WHERE t.id = candidate.id RETURNING t.*
                """).param("max", maxAttempts).param("lease", leaseSeconds).param("token", token)
                .query(MAPPER).optional();
    }

    @Override
    public List<Long> failExpired(int maxAttempts) {
        return jdbc.sql("""
                UPDATE document_ingestion_tasks SET status = 'FAILED', lease_until = NULL,
                    execution_token = NULL, error_code = 'WORKER_INTERRUPTED',
                    error_message = '处理多次中断，请重试', finished_at = CURRENT_TIMESTAMP,
                    gmt_modified = CURRENT_TIMESTAMP
                WHERE status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP AND attempt_count >= :max
                RETURNING document_id
                """).param("max", maxAttempts).query(Long.class).list();
    }

    @Override
    public boolean finish(IngestionTaskDO task, String status, String code, String message, long delayMillis) {
        return jdbc.sql("""
                UPDATE document_ingestion_tasks SET status = :status,
                    error_code = :code, error_message = :message, lease_until = NULL, execution_token = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP + :delay * INTERVAL '1 millisecond',
                    finished_at = CASE WHEN :status = 'QUEUED' THEN NULL ELSE CURRENT_TIMESTAMP END,
                    gmt_modified = CURRENT_TIMESTAMP
                WHERE id = :id AND execution_token = :token AND status = 'PROCESSING'
                    AND lease_until > CURRENT_TIMESTAMP
                """).param("status", status).param("code", code).param("message", message)
                .param("delay", delayMillis).param("id", task.id()).param("token", task.executionToken()).update() == 1;
    }

    @Override
    public boolean retry(long documentId) {
        return jdbc.sql("""
                UPDATE document_ingestion_tasks SET status = 'QUEUED', attempt_count = 0,
                    next_attempt_at = CURRENT_TIMESTAMP, execution_token = NULL, lease_until = NULL,
                    error_code = NULL, error_message = NULL, started_at = NULL, finished_at = NULL,
                    gmt_modified = CURRENT_TIMESTAMP
                WHERE document_id = :id AND status IN ('FAILED', 'UNSUPPORTED')
                """).param("id", documentId).update() == 1;
    }
}
