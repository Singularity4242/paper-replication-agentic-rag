package com.example.paperassistant.model.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record ConversationCreateDTO(
        @NotBlank @Size(max = 200) String title,
        @Size(min = 1, max = 500) List<@NotNull @Positive Long> documentIds) { }
