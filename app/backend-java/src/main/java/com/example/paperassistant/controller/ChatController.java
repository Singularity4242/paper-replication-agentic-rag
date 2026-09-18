package com.example.paperassistant.controller;

import com.example.paperassistant.client.RagChatClient;
import com.example.paperassistant.model.dto.ChatRequestDTO;
import com.example.paperassistant.service.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

@RestController
@Profile("postgres")
@RequestMapping("/api/libraries/{libraryId}/chat")
public class ChatController {
    private final ChatService service;
    private final RagChatClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    public ChatController(ChatService service, RagChatClient client) {
        this.service = service;
        this.client = client;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(@PathVariable @Positive long libraryId,
                                                     @Valid @RequestBody ChatRequestDTO request) {
        // Finish the database transaction and validation before opening SSE.
        var scope = service.prepare(libraryId, request);
        StreamingResponseBody body = output -> {
            RagChatClient.EventSink sink = (event, data) -> {
                output.write(("event: " + event + "\ndata: " + json.writeValueAsString(data) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();
            };
            sink.send("scope", Map.of("libraryId", libraryId, "indexedDocumentCount", scope.documents().size(),
                    "excludedDocuments", scope.excludedDocuments()));
            client.stream(scope, sink);
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no").body(body);
    }
}
