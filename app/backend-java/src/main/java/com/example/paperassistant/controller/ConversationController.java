package com.example.paperassistant.controller;

import com.example.paperassistant.common.response.ApiResponse;
import com.example.paperassistant.model.dto.*;
import com.example.paperassistant.service.ConversationService;
import com.example.paperassistant.service.ConversationStreamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

@RestController
@Profile("postgres")
@RequestMapping("/api/libraries/{libraryId}/conversations")
public class ConversationController {
    private final ConversationService service;
    private final ConversationStreamService streaming;
    private final JsonMapper json = JsonMapper.builder().build();
    public ConversationController(ConversationService service, ConversationStreamService streaming) { this.service = service; this.streaming = streaming; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationDTO> create(@PathVariable @Positive long libraryId, @Valid @RequestBody ConversationCreateDTO request) {
        return ApiResponse.success(service.create(libraryId, request));
    }
    @GetMapping
    public ApiResponse<List<ConversationDTO>> list(@PathVariable @Positive long libraryId,
            @RequestParam(defaultValue = "0") @Min(0) long beforeId, @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.list(libraryId, beforeId, limit));
    }
    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationDTO> get(@PathVariable @Positive long libraryId, @PathVariable @Positive long conversationId) {
        return ApiResponse.success(service.get(libraryId, conversationId));
    }
    @GetMapping("/{conversationId}/messages")
    public ApiResponse<List<ConversationTurnDTO>> messages(@PathVariable @Positive long libraryId, @PathVariable @Positive long conversationId,
            @RequestParam(defaultValue = "0") @Min(0) long afterId, @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(service.messages(libraryId, conversationId, afterId, limit));
    }
    @PostMapping(value = "/{conversationId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> message(@PathVariable @Positive long libraryId, @PathVariable @Positive long conversationId,
                                                         @Valid @RequestBody ConversationMessageDTO request) {
        var prepared = service.begin(libraryId, conversationId, request);
        StreamingResponseBody body = output -> streaming.stream(prepared, (event, value) -> {
            output.write(("event: " + event + "\ndata: " + json.writeValueAsString(value) + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        });
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no").body(body);
    }
}
