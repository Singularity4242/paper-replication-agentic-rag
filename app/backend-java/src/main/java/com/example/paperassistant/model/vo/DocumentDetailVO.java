package com.example.paperassistant.model.vo;

import com.example.paperassistant.model.dto.IngestionTaskDTO;

public record DocumentDetailVO(DocumentVO document, IngestionTaskDTO task) {
}
