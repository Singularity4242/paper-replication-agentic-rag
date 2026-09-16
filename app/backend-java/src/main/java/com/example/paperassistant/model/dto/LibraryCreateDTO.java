package com.example.paperassistant.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Controller 接收并传入 Service 的创建参数。 */
public record LibraryCreateDTO(
        @NotBlank(message = "论文库名称不能为空")
        @Size(max = 128, message = "论文库名称不能超过 128 个字符")
        String name,
        @Size(max = 2000, message = "论文库描述不能超过 2000 个字符")
        String description) {
}
