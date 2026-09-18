package com.example.paperassistant.dao.impl;

import com.example.paperassistant.dao.ConversationDAO;
import com.example.paperassistant.model.dataobject.ConversationDO;
import com.example.paperassistant.model.dataobject.ConversationTurnDO;
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
public class JdbcConversationDAO implements ConversationDAO {
    private final JdbcClient jdbc;
    private static final RowMapper<ConversationDO> CONVERSATION = (rs, n) -> new ConversationDO(rs.getLong("id"),
            rs.getLong("library_id"), rs.getString("title"), rs.getString("document_scope"), rs.getString("checkpoint"),
            rs.getLong("revision"), rs.getObject("gmt_create", OffsetDateTime.class), rs.getObject("gmt_modified", OffsetDateTime.class));
    private static final RowMapper<ConversationTurnDO> TURN = (rs, n) -> new ConversationTurnDO(rs.getLong("id"),
            rs.getLong("conversation_id"), rs.getObject("request_id", UUID.class), rs.getString("question"), rs.getString("status"),
            rs.getString("answer"), rs.getString("error_code"), rs.getString("error_message"), rs.getObject("execution_token", UUID.class),
            rs.getLong("base_revision"), rs.getObject("lease_until", OffsetDateTime.class),
            rs.getObject("gmt_create", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class));

    public JdbcConversationDAO(JdbcClient jdbc) { this.jdbc = jdbc; }

    public ConversationDO create(long libraryId, String title, String documents) {
        return jdbc.sql("INSERT INTO conversations(library_id,title,document_scope) VALUES (:library,:title,CAST(:scope AS jsonb)) RETURNING *")
                .param("library", libraryId).param("title", title).param("scope", documents).query(CONVERSATION).single();
    }
    public Optional<ConversationDO> find(long libraryId, long id, boolean lock) {
        return jdbc.sql("SELECT * FROM conversations WHERE id=:id AND library_id=:library" + (lock ? " FOR UPDATE" : ""))
                .param("id", id).param("library", libraryId).query(CONVERSATION).optional();
    }
    public List<ConversationDO> list(long libraryId, long beforeId, int limit) {
        // Avoid loading internal checkpoints just to display a conversation list.
        return jdbc.sql("SELECT id,library_id,title,document_scope,NULL AS checkpoint,revision,gmt_create,gmt_modified FROM conversations "
                        + "WHERE library_id=:library AND (:before=0 OR id<:before) ORDER BY id DESC LIMIT :limit")
                .param("library", libraryId).param("before", beforeId).param("limit", limit).query(CONVERSATION).list();
    }
    public List<ConversationTurnDO> messages(long conversationId, long afterId, int limit) {
        return jdbc.sql("SELECT * FROM conversation_turns WHERE conversation_id=:id AND id>:after ORDER BY id LIMIT :limit")
                .param("id", conversationId).param("after", afterId).param("limit", limit).query(TURN).list();
    }
    public Optional<ConversationTurnDO> byRequest(long conversationId, UUID requestId) {
        return jdbc.sql("SELECT * FROM conversation_turns WHERE conversation_id=:id AND request_id=:request")
                .param("id", conversationId).param("request", requestId).query(TURN).optional();
    }
    public boolean running(long conversationId) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM conversation_turns WHERE conversation_id=:id AND status='RUNNING')")
                .param("id", conversationId).query(Boolean.class).single();
    }
    public ConversationTurnDO begin(long conversationId, UUID requestId, String question, long revision, long leaseMillis) {
        return jdbc.sql("""
                INSERT INTO conversation_turns(conversation_id,request_id,question,status,execution_token,base_revision,lease_until)
                VALUES (:id,:request,:question,'RUNNING',:token,:revision,clock_timestamp()+:lease * INTERVAL '1 millisecond') RETURNING *
                """).param("id", conversationId).param("request", requestId).param("question", question)
                .param("token", UUID.randomUUID()).param("revision", revision).param("lease", leaseMillis).query(TURN).single();
    }
    public int expire(long conversationId) {
        return jdbc.sql("""
                UPDATE conversation_turns SET status='FAILED',error_code='WORKER_INTERRUPTED',
                    error_message='执行已中断或超时，请使用新的 requestId 重新提问',finished_at=clock_timestamp()
                WHERE conversation_id=:id AND status='RUNNING' AND lease_until<=clock_timestamp()
                """).param("id", conversationId).update();
    }
    public void lock(long conversationId) {
        jdbc.sql("SELECT id FROM conversations WHERE id=:id FOR UPDATE").param("id", conversationId).query(Long.class).optional();
    }
    public List<Long> expiredConversations(int limit) {
        return jdbc.sql("SELECT conversation_id FROM conversation_turns WHERE status='RUNNING' AND lease_until<=clock_timestamp() LIMIT :limit")
                .param("limit", limit).query(Long.class).list();
    }
    public int complete(ConversationTurnDO turn, String answer) {
        return jdbc.sql("""
                UPDATE conversation_turns SET status='COMPLETED',answer=CAST(:answer AS jsonb),finished_at=clock_timestamp()
                WHERE id=:id AND execution_token=:token AND status='RUNNING' AND lease_until>clock_timestamp()
                """).param("id", turn.id()).param("token", turn.executionToken()).param("answer", answer).update();
    }
    public int fail(ConversationTurnDO turn, String code, String message) {
        return jdbc.sql("""
                UPDATE conversation_turns SET status='FAILED',error_code=:code,error_message=:message,finished_at=clock_timestamp()
                WHERE id=:id AND execution_token=:token AND status='RUNNING'
                """).param("id", turn.id()).param("token", turn.executionToken()).param("code", code).param("message", message).update();
    }
    public int saveCheckpoint(long conversationId, long revision, String checkpoint) {
        return jdbc.sql("""
                UPDATE conversations SET checkpoint=CAST(:checkpoint AS jsonb),revision=revision+1,gmt_modified=clock_timestamp()
                WHERE id=:id AND revision=:revision
                """).param("id", conversationId).param("revision", revision).param("checkpoint", checkpoint).update();
    }
}
