package com.example.paperassistant.model.vo;

import java.time.OffsetDateTime;

/** HTTP 响应中的论文库视图，将接口字段与数据库字段隔离。 */
public record LibraryVO(
        Long id,
        String name,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
