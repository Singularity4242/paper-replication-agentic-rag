package com.example.paperassistant.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatRequestDTO(
        @NotBlank(message = "问题不能为空") @Size(max = 8000, message = "问题不能超过 8000 字") String question,
        @Size(min = 1, max = 500, message = "指定文档数量须为 1 到 500；查询整库时请省略该字段")
        List<@NotNull @Positive Long> documentIds) {
}
