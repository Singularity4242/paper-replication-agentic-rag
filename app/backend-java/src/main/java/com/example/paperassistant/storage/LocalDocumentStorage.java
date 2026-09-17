package com.example.paperassistant.storage;

import com.example.paperassistant.common.exception.FileStorageException;
import com.example.paperassistant.common.exception.InvalidDocumentException;
import com.example.paperassistant.common.exception.DocumentTooLargeException;
import com.example.paperassistant.config.DocumentStorageProperties;
import com.example.paperassistant.model.dto.DocumentUploadDTO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 流式落盘和内容哈希；原文件名不会成为物理路径。 */
@Component
@Profile("postgres")
@EnableConfigurationProperties(DocumentStorageProperties.class)
public class LocalDocumentStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalDocumentStorage.class);
    private final DocumentStorageProperties properties;

    public LocalDocumentStorage(DocumentStorageProperties properties) {
        this.properties = properties;
    }

    public StoredDocument store(DocumentUploadDTO upload) {
        String name = upload.originalFilename();
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.length() > 255 || name.contains("/")
                || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidDocumentException("文件名不合法");
        }
        // 不按扩展名或文件头筛选类型；点文件和无扩展名文件也可以上传。
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && dot < name.length() - 1 ? name.substring(dot) : "";
        String key = UUID.randomUUID() + extension;
        if (key.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new InvalidDocumentException("文件扩展名过长");
        }
        if (upload.declaredSize() > properties.maxFileSize().toBytes()) {
            throw new DocumentTooLargeException();
        }
        Path temporary = null;
        try {
            Files.createDirectories(properties.root());
            temporary = Files.createTempFile(properties.root(), ".pending-", ".upload");
            MessageDigest digest = sha256();
            long size = 0;
            try (var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = upload.inputStream().read(buffer)) != -1) {
                    size += length;
                    if (size > properties.maxFileSize().toBytes()) {
                        throw new DocumentTooLargeException();
                    }
                    output.write(buffer, 0, length);
                    digest.update(buffer, 0, length);
                }
            }
            if (size == 0) {
                throw new InvalidDocumentException("文件不能为空");
            }
            Files.move(temporary, properties.root().resolve(key), StandardCopyOption.ATOMIC_MOVE);
            return new StoredDocument(key, name, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            throw new FileStorageException(exception);
        } finally {
            if (temporary != null) {
                deleteQuietly(temporary);
            }
        }
    }

    /** 普通事务回滚时补偿文件；清理失败保留日志，不能把失败伪装成成功。 */
    public void discard(StoredDocument document) {
        String key = document.storageKey();
        if (key == null || !key.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}(\\.[^./\\\\]+)?")
                || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("非法的内部存储标识");
        }
        deleteQuietly(properties.root().resolve(key));
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.error("文件补偿清理失败，需要检查存储目录：{}", path, exception);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256 支持", exception);
        }
    }
}
