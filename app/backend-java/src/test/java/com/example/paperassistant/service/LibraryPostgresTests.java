package com.example.paperassistant.service;

import com.example.paperassistant.dao.LibraryDAO;
import com.example.paperassistant.common.exception.ResourceNotFoundException;
import com.example.paperassistant.model.dataobject.LibraryDO;
import com.example.paperassistant.model.dto.LibraryCreateDTO;
import com.example.paperassistant.model.dto.LibraryDTO;
import com.example.paperassistant.model.dto.LibraryUpdateDTO;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/** 使用脚本创建的临时 PostgreSQL 库验证真实 SQL 和 Service 事务。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class LibraryPostgresTests {

    @Autowired
    private LibraryService libraryService;

    @Autowired
    private JdbcClient jdbcClient;

    @MockitoSpyBean
    private LibraryDAO libraryDAO;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DATABASE_URL");
        // 在 Spring / Flyway 接触数据库之前，拒绝日常库或不符合约定的连接。
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:5432/paper_libraries_test_[a-f0-9]{12}")) {
            throw new IllegalArgumentException("请使用 scripts/verify_postgres.py 创建隔离测试数据库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @BeforeEach
    void clearTestRows() {
        jdbcClient.sql("DELETE FROM documents").update();
        jdbcClient.sql("DELETE FROM paper_libraries").update();
    }

    @Test
    void generatedFieldsOptionalDescriptionAndBoundParametersRoundTrip() {
        String name = "研究者's RAG'; DROP TABLE paper_libraries; --";
        LibraryDTO created = libraryService.createLibrary(new LibraryCreateDTO("  " + name + "  ", null));

        assertTrue(created.id() > 0);
        assertEquals(name, created.name());
        assertNull(created.description());
        assertNotNull(created.gmtCreate());
        assertNotNull(created.gmtModified());
        assertEquals(List.of(created), libraryService.listLibraries());
        assertEquals(1L, jdbcClient.sql("SELECT count(*) FROM paper_libraries").query(Long.class).single());
    }

    @Test
    void listIsEmptyInitiallyAndUsesDescendingIdForEqualCreationTimes() {
        assertTrue(libraryService.listLibraries().isEmpty());
        LibraryDTO first = libraryService.createLibrary(new LibraryCreateDTO("同名论文库", "第一条"));
        LibraryDTO second = libraryService.createLibrary(new LibraryCreateDTO("同名论文库", "第二条"));
        jdbcClient.sql("UPDATE paper_libraries SET gmt_create = TIMESTAMPTZ '2026-01-01 00:00:00+00'").update();

        List<LibraryDTO> libraries = libraryService.listLibraries();
        assertEquals(List.of(second.id(), first.id()), libraries.stream().map(LibraryDTO::id).toList());
        assertEquals("第二条", libraries.getFirst().description());
    }

    @Test
    void serviceTransactionRollsBackWhenAnExceptionOccursAfterInsert() {
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            invocation.callRealMethod();
            // 故意在真实 INSERT 成功后抛错，确认记录不会残留。
            throw new IllegalStateException("模拟写入后的业务失败");
        }).when(libraryDAO).insertLibrary(any(LibraryDO.class));

        assertThrows(IllegalStateException.class,
                () -> libraryService.createLibrary(new LibraryCreateDTO("必须回滚", null)));
        assertEquals(0L, jdbcClient.sql("SELECT count(*) FROM paper_libraries").query(Long.class).single());
    }

    @Test
    void updateKeepsCreationTimeRefreshesModificationTimeAndOnlyChangesTheTarget() {
        LibraryDTO target = libraryService.createLibrary(new LibraryCreateDTO("旧名称", "原描述"));
        LibraryDTO other = libraryService.createLibrary(new LibraryCreateDTO("保留的库", "保留的描述"));
        jdbcClient.sql("UPDATE paper_libraries SET gmt_modified = TIMESTAMPTZ '2000-01-01 00:00:00+00' WHERE id = :id")
                .param("id", target.id()).update();

        String name = "新名称'; DELETE FROM paper_libraries; --";
        LibraryDTO updated = libraryService.updateLibrary(target.id(), new LibraryUpdateDTO("  " + name + "  ", null));

        assertEquals(target.id(), updated.id());
        assertEquals(name, updated.name());
        assertNull(updated.description());
        assertEquals(target.gmtCreate(), updated.gmtCreate());
        assertTrue(updated.gmtModified().isAfter(OffsetDateTime.parse("2000-01-01T00:00:00Z")));
        assertEquals(List.of(other, updated), libraryService.listLibraries());
    }

    @Test
    void deleteOnlyRemovesTheTargetAndMissingMutationsFail() {
        LibraryDTO target = libraryService.createLibrary(new LibraryCreateDTO("待删除", null));
        LibraryDTO other = libraryService.createLibrary(new LibraryCreateDTO("保留", null));
        libraryService.deleteLibrary(target.id());

        assertEquals(List.of(other), libraryService.listLibraries());
        assertThrows(ResourceNotFoundException.class, () -> libraryService.deleteLibrary(target.id()));
        assertThrows(ResourceNotFoundException.class,
                () -> libraryService.updateLibrary(target.id(), new LibraryUpdateDTO("不存在", null)));
        assertEquals(List.of(other), libraryService.listLibraries());
    }

    @Test
    void updateAndDeleteRollBackIfAnExceptionOccursAfterTheSql() {
        LibraryDTO original = libraryService.createLibrary(new LibraryCreateDTO("必须保留", "原描述"));
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            invocation.callRealMethod();
            throw new IllegalStateException("模拟更新后的失败");
        }).when(libraryDAO).updateLibrary(any(LibraryDO.class));
        assertThrows(IllegalStateException.class,
                () -> libraryService.updateLibrary(original.id(), new LibraryUpdateDTO("必须回滚", null)));
        assertEquals(List.of(original), libraryService.listLibraries());

        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            invocation.callRealMethod();
            throw new IllegalStateException("模拟删除后的失败");
        }).when(libraryDAO).deleteLibrary(anyLong());
        assertThrows(IllegalStateException.class, () -> libraryService.deleteLibrary(original.id()));
        assertEquals(List.of(original), libraryService.listLibraries());
    }
}
