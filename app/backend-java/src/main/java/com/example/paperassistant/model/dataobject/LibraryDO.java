package com.example.paperassistant.model.dataobject;

import java.time.OffsetDateTime;

/** 对应 paper_libraries 表的一行数据，禁止直接用作 HTTP 响应。 */
public record LibraryDO(
        Long id,
        String name,
        String description,
        OffsetDateTime gmtCreate,
        OffsetDateTime gmtModified) {
}
