package com.example.paperassistant.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PUT 替换名称和描述；描述省略或为 null 时清空原描述。 */
public record LibraryUpdateDTO(
        @NotBlank(message = "论文库名称不能为空")
        @Size(max = 128, message = "论文库名称不能超过 128 个字符")
        String name,
        @Size(max = 2000, message = "论文库描述不能超过 2000 个字符")
        String description) {
}
