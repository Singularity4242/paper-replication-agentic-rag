package com.example.paperassistant.model.dto;

import java.time.OffsetDateTime;

/** Service 返回的资料元数据，不包含内部存储位置。 */
public record DocumentDTO(Long id, long libraryId, String originalFilename, long fileSize,
                       String sha256, String status, String indexStatus, OffsetDateTime gmtCreate, OffsetDateTime gmtModified) {
}
