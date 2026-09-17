package com.example.paperassistant.service;

import com.example.paperassistant.common.exception.ConflictException;
import com.example.paperassistant.common.exception.ResourceNotFoundException;
import com.example.paperassistant.dao.LibraryDAO;
import com.example.paperassistant.dao.DocumentDAO;
import com.example.paperassistant.model.dataobject.DocumentDO;
import com.example.paperassistant.model.dto.LibraryCreateDTO;
import com.example.paperassistant.model.dto.DocumentDTO;
import com.example.paperassistant.model.dto.DocumentUploadDTO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "paper.ingestion.enabled=false")
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class DocumentPostgresTests {
    @Autowired DocumentService documentService;
    @Autowired LibraryService libraryService;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean DocumentDAO documentDAO;
    @MockitoSpyBean LibraryDAO libraryDAO;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getenv("TEST_DATABASE_URL");
        String root = System.getenv("TEST_STORAGE_ROOT");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:5432/paper_libraries_test_[a-f0-9]{12}")
                || root == null || !Path.of(root).getFileName().toString().startsWith("paper-storage-test-")) {
            throw new IllegalArgumentException("请通过 verify_postgres.py 配置专用临时数据库和文件目录");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
        registry.add("paper.storage.root", () -> root);
    }

    @BeforeEach
    void clear() throws Exception {
        jdbc.sql("DELETE FROM documents").update();
        jdbc.sql("DELETE FROM paper_libraries").update();
        Files.createDirectories(root());
        try (var files = Files.list(root())) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    @Test
    void storesBytesDeduplicatesWithinLibraryAndSeparatesLists() throws Exception {
        long first = library();
        long second = library();
        assertTrue(documentService.listDocuments(first).isEmpty());
        DocumentDTO paper = upload(first);
        assertEquals("UPLOADED", paper.status());
        assertEquals("QUEUED", paper.indexStatus());
        assertEquals(List.of(paper), documentService.listDocuments(first));
        String key = jdbc.sql("SELECT storage_key FROM documents WHERE id = :id").param("id", paper.id()).query(String.class).single();
        assertArrayEquals(bytes(), Files.readAllBytes(root().resolve(key)));
        ConflictException duplicate = assertThrows(ConflictException.class, () -> upload(first));
        assertEquals("DUPLICATE_DOCUMENT", duplicate.getCode());
        assertEquals(1, fileCount());
        DocumentDTO other = upload(second);
        assertEquals(paper.sha256(), other.sha256());
        assertEquals(List.of(paper), documentService.listDocuments(first));
        assertEquals(List.of(other), documentService.listDocuments(second));
        assertEquals(2, fileCount());
    }

    @Test
    void mixedResourcesAreListedAndPreventLibraryDeletion() {
        long library = library();
        for (String filename : List.of("README.md", "config.yaml", "train.py", "model.bin", "LICENSE", ".env")) {
            byte[] data = filename.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            DocumentDTO document = documentService.uploadDocument(library,
                    new DocumentUploadDTO(filename, data.length, new ByteArrayInputStream(data)));
            assertEquals(filename, document.originalFilename());
            assertEquals("UPLOADED", document.status());
            assertEquals("QUEUED", document.indexStatus());
        }
        assertEquals(6, documentService.listDocuments(library).size());
        assertEquals("LIBRARY_NOT_EMPTY", assertThrows(ConflictException.class,
                () -> libraryService.deleteLibrary(library)).getCode());
    }

    @Test
    void migratesExistingV2DataWithoutChangingIdentityStorageOrConstraints() throws Exception {
        String schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        String key = UUID.randomUUID() + ".pdf";
        Path file = root().resolve(key);
        Files.write(file, bytes());
        try {
            Flyway.configure().dataSource(System.getenv("TEST_DATABASE_URL"), System.getenv("TEST_DATABASE_USER"),
                    System.getenv("TEST_DATABASE_PASSWORD")).schemas(schema).defaultSchema(schema)
                    .target("2").load().migrate();
            long library = jdbc.sql("INSERT INTO " + schema + ".paper_libraries (name) VALUES ('existing') RETURNING id")
                    .query(Long.class).single();
            var original = jdbc.sql("INSERT INTO " + schema + ".papers (library_id, original_filename, storage_key, file_size, sha256) "
                    + "VALUES (:library, 'existing.pdf', :key, :size, :sha) RETURNING *")
                    .param("library", library).param("key", key).param("size", bytes().length)
                    .param("sha", "a".repeat(64)).query().singleRow();
            Flyway latest = Flyway.configure().dataSource(System.getenv("TEST_DATABASE_URL"), System.getenv("TEST_DATABASE_USER"),
                    System.getenv("TEST_DATABASE_PASSWORD")).schemas(schema).defaultSchema(schema).load();
            assertEquals(2, latest.migrate().migrationsExecuted);
            var upgraded = jdbc.sql("SELECT * FROM " + schema + ".documents").query().singleRow();
            for (var entry : original.entrySet()) {
                if (!entry.getKey().equals("index_status")) {
                    assertEquals(entry.getValue(), upgraded.get(entry.getKey()), entry.getKey());
                }
            }
            assertEquals("QUEUED", upgraded.get("index_status"));
            assertArrayEquals(bytes(), Files.readAllBytes(file));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.sql("DELETE FROM " + schema + ".paper_libraries").update());
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.sql("INSERT INTO " + schema + ".documents (library_id, original_filename, storage_key, file_size, sha256) "
                            + "VALUES (:library, 'duplicate.yaml', 'other.yaml', 5, :sha)")
                            .param("library", library).param("sha", "a".repeat(64)).update());
            long nextId = jdbc.sql("INSERT INTO " + schema + ".documents (library_id, original_filename, storage_key, file_size, sha256) "
                    + "VALUES (:library, 'new.yaml', 'new.yaml', 5, :sha) RETURNING id")
                    .param("library", library).param("sha", "b".repeat(64)).query(Long.class).single();
            assertTrue(nextId > ((Number) original.get("id")).longValue());
            assertEquals(0, latest.migrate().migrationsExecuted);
        } finally {
            jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
            Files.deleteIfExists(file);
        }
    }

    @Test
    void missingLibraryDoesNotStoreFiles() throws Exception {
        assertThrows(ResourceNotFoundException.class, () -> upload(Long.MAX_VALUE));
        assertThrows(ResourceNotFoundException.class, () -> documentService.listDocuments(Long.MAX_VALUE));
        assertEquals(0, fileCount());
    }

    @Test
    void databaseFailureRollsBackRowAndCompensatesFile() throws Exception {
        long library = library();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("模拟插入后的失败");
        }).when(documentDAO).insertDocument(any(DocumentDO.class));
        assertThrows(IllegalStateException.class, () -> upload(library));
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single());
        assertEquals(0, fileCount());
    }

    @Test
    void foreignKeyBlocksNonemptyDeletionEvenWithoutServiceCheck() {
        long library = library();
        DocumentDTO paper = upload(library);
        assertEquals("LIBRARY_NOT_EMPTY", assertThrows(ConflictException.class,
                () -> libraryService.deleteLibrary(library)).getCode());
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.sql("DELETE FROM paper_libraries WHERE id = :id").param("id", library).update());
        assertEquals(List.of(paper), documentService.listDocuments(library));
    }

    @Test
    void concurrentDuplicatesLeaveExactlyOneRowAndOneFile() throws Exception {
        long library = library();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> uploadAfter(start, library));
            var second = executor.submit(() -> uploadAfter(start, library));
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, documentService.listDocuments(library).size());
        assertEquals(1, fileCount());
    }

    @Test
    void uploadWinsRaceAndConcurrentDeleteReturnsConflict() throws Exception {
        long library = library();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            inserted.countDown();
            assertTrue(deleteStarted.await(5, TimeUnit.SECONDS));
            return result;
        }).when(documentDAO).insertDocument(any(DocumentDO.class));
        doAnswer(invocation -> {
            deleteStarted.countDown();
            return invocation.callRealMethod();
        }).when(libraryDAO).deleteLibrary(library);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var uploaded = executor.submit(() -> upload(library));
            assertTrue(inserted.await(5, TimeUnit.SECONDS));
            var deleted = executor.submit(() -> assertThrows(ConflictException.class,
                    () -> libraryService.deleteLibrary(library)).getCode());
            DocumentDTO saved = uploaded.get(10, TimeUnit.SECONDS);
            assertEquals("LIBRARY_NOT_EMPTY", deleted.get(10, TimeUnit.SECONDS));
            assertEquals(List.of(saved), documentService.listDocuments(library));
        }
        assertEquals(1, fileCount());
    }

    @Test
    void deleteWinsRaceAndUploadCleansItsAlreadySavedFile() throws Exception {
        long library = library();
        CountDownLatch stored = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);
        doAnswer(invocation -> {
            stored.countDown();
            assertTrue(deleted.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(libraryDAO).lockById(library);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var upload = executor.submit(() -> assertThrows(ResourceNotFoundException.class, () -> upload(library)));
            try {
                assertTrue(stored.await(5, TimeUnit.SECONDS));
                libraryService.deleteLibrary(library);
            } finally {
                deleted.countDown();
            }
            upload.get(10, TimeUnit.SECONDS);
        }
        assertEquals(0, fileCount());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM documents").query(Long.class).single());
    }

    private int uploadAfter(CountDownLatch start, long id) throws Exception {
        start.await();
        try {
            upload(id);
            return 1;
        } catch (ConflictException duplicate) {
            assertEquals("DUPLICATE_DOCUMENT", duplicate.getCode());
            return 0;
        }
    }

    private long library() {
        return libraryService.createLibrary(new LibraryCreateDTO("测试论文库", null)).id();
    }

    private DocumentDTO upload(long library) {
        return documentService.uploadDocument(library, new DocumentUploadDTO("reader's.pdf", bytes().length, new ByteArrayInputStream(bytes())));
    }

    private static byte[] bytes() {
        return "%PDF-1.7\nfixture\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private Path root() {
        return Path.of(System.getenv("TEST_STORAGE_ROOT"));
    }

    private long fileCount() throws Exception {
        try (var files = Files.list(root())) {
            return files.count();
        }
    }
}
