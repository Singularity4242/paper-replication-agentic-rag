package com.example.paperassistant.dao.impl;

import com.example.paperassistant.dao.DocumentDAO;
import com.example.paperassistant.model.dataobject.DocumentDO;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@Profile("postgres")
public class JdbcDocumentDAO implements DocumentDAO {
    private static final RowMapper<DocumentDO> ROW_MAPPER = (rs, rowNum) -> new DocumentDO(
            rs.getLong("id"), rs.getLong("library_id"), rs.getString("original_filename"),
            rs.getString("storage_key"), rs.getLong("file_size"), rs.getString("sha256"),
            rs.getString("status"), rs.getString("index_status"), rs.getObject("gmt_create", OffsetDateTime.class),
            rs.getObject("gmt_modified", OffsetDateTime.class));

    private final JdbcClient jdbcClient;

    public JdbcDocumentDAO(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean existsByLibraryAndSha256(long libraryId, String sha256) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM documents WHERE library_id = :libraryId AND sha256 = :sha256)")
                .param("libraryId", libraryId).param("sha256", sha256).query(Boolean.class).single();
    }

    @Override
    public DocumentDO insertDocument(DocumentDO document) {
        return jdbcClient.sql("""
                INSERT INTO documents (library_id, original_filename, storage_key, file_size, sha256)
                VALUES (:libraryId, :filename, :storageKey, :size, :sha256)
                RETURNING id, library_id, original_filename, storage_key, file_size, sha256,
                          status, index_status, gmt_create, gmt_modified
                """)
                .param("libraryId", document.libraryId()).param("filename", document.originalFilename())
                .param("storageKey", document.storageKey()).param("size", document.fileSize())
                .param("sha256", document.sha256()).query(ROW_MAPPER).single();
    }

    @Override
    public List<DocumentDO> listDocuments(long libraryId) {
        return jdbcClient.sql("""
                SELECT id, library_id, original_filename, storage_key, file_size, sha256,
                       status, index_status, gmt_create, gmt_modified
                FROM documents WHERE library_id = :libraryId
                ORDER BY gmt_create DESC, id DESC
                """).param("libraryId", libraryId).query(ROW_MAPPER).list();
    }
}
