package com.example.paperassistant.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** 本地原文件目录和单文件上限。 */
@ConfigurationProperties("paper.storage")
public record DocumentStorageProperties(Path root, DataSize maxFileSize) {
    public DocumentStorageProperties {
        if (root == null || maxFileSize == null || maxFileSize.toBytes() < 1) {
            throw new IllegalArgumentException("必须配置存储目录及有效的文件大小上限");
        }
        root = root.toAbsolutePath().normalize();
    }
}
