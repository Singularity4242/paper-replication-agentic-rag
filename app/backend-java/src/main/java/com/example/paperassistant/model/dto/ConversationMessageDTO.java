package com.example.paperassistant.model.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record ConversationMessageDTO(@NotNull UUID requestId, @NotBlank @Size(max = 8000) String question) { }
