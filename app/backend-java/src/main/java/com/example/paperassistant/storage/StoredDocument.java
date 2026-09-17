package com.example.paperassistant.storage;

/** 文件存储成功后的内部结果。 */
public record StoredDocument(String storageKey, String originalFilename, long size, String sha256) {
}
