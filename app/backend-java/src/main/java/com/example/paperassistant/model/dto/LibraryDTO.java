package com.example.paperassistant.model.dto;

import java.time.OffsetDateTime;

/** Service 向调用方传输的论文库数据。 */
public record LibraryDTO(
        Long id,
        String name,
        String description,
        OffsetDateTime gmtCreate,
        OffsetDateTime gmtModified) {
}
