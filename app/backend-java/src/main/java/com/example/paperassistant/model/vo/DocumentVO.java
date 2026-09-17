package com.example.paperassistant.model.vo;

import java.time.OffsetDateTime;

/** 对外展示的资料信息；UPLOADED 不表示已可检索。 */
public record DocumentVO(Long id, long libraryId, String originalFilename, long fileSize,
                      String sha256, String status, String indexStatus, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
