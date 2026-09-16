package com.example.paperassistant.dao.impl;

import com.example.paperassistant.dao.LibraryDAO;
import com.example.paperassistant.model.dataobject.LibraryDO;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 基于 Spring JDBC 的数据访问实现，使用参数绑定及显式字段映射。 */
@Repository
@Profile("postgres")
public class JdbcLibraryDAO implements LibraryDAO {

    private static final RowMapper<LibraryDO> ROW_MAPPER = (resultSet, rowNum) -> new LibraryDO(
            resultSet.getLong("id"),
            resultSet.getString("name"),
            resultSet.getString("description"),
            resultSet.getObject("gmt_create", OffsetDateTime.class),
            resultSet.getObject("gmt_modified", OffsetDateTime.class));

    private final JdbcClient jdbcClient;

    public JdbcLibraryDAO(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public LibraryDO insertLibrary(LibraryDO library) {
        return jdbcClient.sql("""
                INSERT INTO paper_libraries (name, description)
                VALUES (:name, :description)
                RETURNING id, name, description, gmt_create, gmt_modified
                """)
                .param("name", library.name())
                .param("description", library.description(), Types.VARCHAR)
                .query(ROW_MAPPER)
                .single();
    }

    @Override
    public List<LibraryDO> listLibraries() {
        return jdbcClient.sql("""
                SELECT id, name, description, gmt_create, gmt_modified
                FROM paper_libraries
                ORDER BY gmt_create DESC, id DESC
                """)
                .query(ROW_MAPPER)
                .list();
    }

    @Override
    public Optional<LibraryDO> updateLibrary(LibraryDO library) {
        return jdbcClient.sql("""
                UPDATE paper_libraries
                SET name = :name, description = :description, gmt_modified = CURRENT_TIMESTAMP
                WHERE id = :id
                RETURNING id, name, description, gmt_create, gmt_modified
                """)
                .param("id", library.id())
                .param("name", library.name())
                .param("description", library.description(), Types.VARCHAR)
                .query(ROW_MAPPER)
                .optional();
    }

    @Override
    public int deleteLibrary(long id) {
        return jdbcClient.sql("DELETE FROM paper_libraries WHERE id = :id")
                .param("id", id)
                .update();
    }
}
