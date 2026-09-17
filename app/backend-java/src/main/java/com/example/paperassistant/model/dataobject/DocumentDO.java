package com.example.paperassistant.model.dataobject;

import java.time.OffsetDateTime;

/** documents 表记录；storageKey 仅在后端使用。 */
public record DocumentDO(Long id, long libraryId, String originalFilename, String storageKey,
                      long fileSize, String sha256, String status, String indexStatus,
                      OffsetDateTime gmtCreate, OffsetDateTime gmtModified) {
}
