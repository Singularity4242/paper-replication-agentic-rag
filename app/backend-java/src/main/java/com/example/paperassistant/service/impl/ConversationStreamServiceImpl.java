package com.example.paperassistant.service.impl;

import com.example.paperassistant.client.RagChatClient;
import com.example.paperassistant.service.ConversationService;
import com.example.paperassistant.service.ConversationStreamService;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("postgres")
public class ConversationStreamServiceImpl implements ConversationStreamService {
    private final ConversationService service;
    private final RagChatClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    public ConversationStreamServiceImpl(ConversationService service, RagChatClient client) { this.service = service; this.client = client; }

    @Override
    public void stream(ConversationService.PreparedTurn prepared, RagChatClient.EventSink sink) throws IOException {
        var turn = prepared.turn();
        if (prepared.replay()) {
            sink.send("turn", Map.of("conversationId", turn.conversationId(), "turnId", turn.id(), "requestId", turn.requestId(), "replayed", true));
            if ("COMPLETED".equals(turn.status())) {
                sink.send("answer", json.readTree(turn.answer()));
                sink.send("done", Map.of("status", "COMPLETED"));
            } else { sink.send("error", Map.of("code", turn.errorCode(), "message", turn.errorMessage())); }
            return;
        }
        var state = new Completion();
        try {
            sink.send("turn", Map.of("conversationId", turn.conversationId(), "turnId", turn.id(), "requestId", turn.requestId(), "replayed", false));
            client.streamConversation(prepared.scope(), turn.conversationId(), prepared.checkpoint(), (event, value) -> {
                switch (event) {
                    case "answer" -> state.answer = json.valueToTree(value);
                    case "checkpoint" -> state.checkpoint = json.valueToTree(value);
                    case "done" -> {
                        if (state.answer == null || state.checkpoint == null) { throw new IOException("Incomplete turn"); }
                        // Persist the answer and checkpoint in one short transaction before acknowledging success.
                        service.complete(prepared, state.answer, state.checkpoint);
                        state.completed = true;
                        sink.send("answer", state.answer);
                        sink.send("done", value);
                    }
                    case "error" -> {
                        JsonNode error = json.valueToTree(value);
                        service.fail(prepared, error.path("code").asString(), error.path("message").asString());
                        state.failed = true;
                        sink.send(event, value);
                    }
                    default -> sink.send(event, value);
                }
            });
            if (!state.completed && !state.failed) { throw new IOException("Missing terminal event"); }
        } catch (IOException exception) {
            if (!state.completed) { service.fail(prepared, "STREAM_INTERRUPTED", "连接已中断，该轮未完成，请使用新的 requestId 重试"); }
            throw exception;
        } catch (RuntimeException exception) {
            if (!state.completed) {
                try { service.fail(prepared, "TURN_SAVE_FAILED", "该轮结果未保存，请查询消息状态后重试"); }
                catch (RuntimeException ignored) { /* The durable lease allows recovery when PostgreSQL returns. */ }
                sink.send("error", Map.of("code", "TURN_SAVE_FAILED", "message", "结果保存失败，请查询消息状态后重试"));
            } else { throw exception; }
        }
    }

    private static class Completion {
        JsonNode answer;
        JsonNode checkpoint;
        boolean completed;
        boolean failed;
    }
}
