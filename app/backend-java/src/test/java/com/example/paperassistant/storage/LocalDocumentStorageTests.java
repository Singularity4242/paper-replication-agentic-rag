package com.example.paperassistant.storage;

import com.example.paperassistant.common.exception.FileStorageException;
import com.example.paperassistant.common.exception.InvalidDocumentException;
import com.example.paperassistant.common.exception.DocumentTooLargeException;
import com.example.paperassistant.config.DocumentStorageProperties;
import com.example.paperassistant.model.dto.DocumentUploadDTO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.*;

class LocalDocumentStorageTests {
    @TempDir
    Path root;

    private LocalDocumentStorage storage() {
        return new LocalDocumentStorage(new DocumentStorageProperties(root, DataSize.ofBytes(64)));
    }

    @Test
    void savesOriginalBytesWithGeneratedKeyAndCorrectHash() throws Exception {
        byte[] bytes = "%PDF-1.7\nfixture\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        StoredDocument stored = storage().store(new DocumentUploadDTO("paper.PDF", bytes.length, new ByteArrayInputStream(bytes)));
        assertTrue(stored.storageKey().matches("[a-f0-9-]{36}\\.PDF"));
        assertEquals(bytes.length, stored.size());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), stored.sha256());
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve(stored.storageKey())));
    }

    @Test
    void rejectsEmptyFilesAndInvalidNamesWithoutLeavingFiles() throws Exception {
        for (String name : List.of(".", "..", " ", "../paper.pdf", "C:\\paper.pdf", "bad\n.pdf", "a".repeat(256) + ".pdf")) {
            assertThrows(InvalidDocumentException.class,
                    () -> storage().store(new DocumentUploadDTO(name, 5, new ByteArrayInputStream("%PDF-".getBytes()))));
        }
        for (byte[] bytes : List.of(new byte[0])) {
            assertThrows(InvalidDocumentException.class,
                    () -> storage().store(new DocumentUploadDTO("paper.pdf", bytes.length, new ByteArrayInputStream(bytes))));
        }
        assertEmpty();
    }

    @Test
    void acceptsAllFileTypesPreservesExtensionsAndCanDiscardThem() throws Exception {
        for (String name : List.of("notes.md", "config.yaml", "settings.json", "main.py", "results.csv",
                "archive.zip", "model.safetensors", "data.unknown", "README", ".env", "paper.PDF", "说明.配置")) {
            // 含 NUL 和非 UTF-8 字节；上传层不解析内容，也不验证扩展名对应的格式。
            byte[] bytes = new byte[] {0, 1, (byte) 0xff, 13, 10};
            StoredDocument stored = storage().store(new DocumentUploadDTO(name, bytes.length, new ByteArrayInputStream(bytes)));
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 ? name.substring(dot) : "";
            assertEquals(extension, stored.storageKey().substring(36));
            assertEquals(name, stored.originalFilename());
            assertArrayEquals(bytes, Files.readAllBytes(root.resolve(stored.storageKey())));
            storage().discard(stored);
            assertEmpty();
        }
        var oneByte = new LocalDocumentStorage(new DocumentStorageProperties(root, DataSize.ofBytes(1)));
        var stored = oneByte.store(new DocumentUploadDTO("flag", 1, new ByteArrayInputStream(new byte[] {1})));
        assertEquals(1, stored.size());
    }

    @Test
    void discardRejectsPathsOutsideGeneratedKeys() {
        for (String key : List.of("../outside", "/tmp/outside", "00000000-0000-0000-0000-000000000000/../../outside")) {
            assertThrows(IllegalArgumentException.class,
                    () -> storage().discard(new StoredDocument(key, "file", 1, "unused")));
        }
    }

    @Test
    void enforcesActualStreamSizeEvenIfDeclaredSizeIsWrong() throws Exception {
        byte[] bytes = ("%PDF-" + "a".repeat(100)).getBytes();
        assertThrows(DocumentTooLargeException.class,
                () -> storage().store(new DocumentUploadDTO("paper.pdf", 1, new ByteArrayInputStream(bytes))));
        assertEmpty();
    }

    @Test
    void readFailureCleansUpThePartialFile() throws Exception {
        InputStream broken = new InputStream() {
            private int offset;
            @Override
            public int read() throws IOException {
                if (offset < 5) {
                    return "%PDF-".charAt(offset++);
                }
                throw new IOException("模拟传输中断");
            }
        };
        assertThrows(FileStorageException.class, () -> storage().store(new DocumentUploadDTO("paper.pdf", 10, broken)));
        assertEmpty();
    }

    @Test
    void unavailableDirectoryFailsWithoutClaimingSuccess() throws Exception {
        Path file = Files.createFile(root.resolve("not-a-directory"));
        LocalDocumentStorage storage = new LocalDocumentStorage(new DocumentStorageProperties(file, DataSize.ofBytes(64)));
        assertThrows(FileStorageException.class,
                () -> storage.store(new DocumentUploadDTO("paper.pdf", 5, new ByteArrayInputStream("%PDF-".getBytes()))));
    }

    private void assertEmpty() throws IOException {
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
    }
}
