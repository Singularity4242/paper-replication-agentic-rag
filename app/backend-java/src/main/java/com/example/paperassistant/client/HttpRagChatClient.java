package com.example.paperassistant.client;

import com.example.paperassistant.config.ChatProperties;
import com.example.paperassistant.config.IngestionProperties;
import com.example.paperassistant.model.dto.ChatScopeDTO;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 一次模型运行只发送一次；超时、断流不会自动重放已输出的回答。 */
@Component
@Profile("postgres")
@EnableConfigurationProperties(ChatProperties.class)
public class HttpRagChatClient implements RagChatClient {
    private static final int MAX_FRAME = 262144;
    private static final int MAX_STREAM = 4 * 1024 * 1024;
    private final IngestionProperties ingestion;
    private final ChatProperties properties;
    private final Semaphore slots;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("chat-deadline").factory());
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpRagChatClient(IngestionProperties ingestion, ChatProperties properties) {
        this.ingestion = ingestion;
        this.properties = properties;
        slots = new Semaphore(properties.concurrency());
    }

    @Override
    public void stream(ChatScopeDTO scope, EventSink sink) throws IOException {
        streamInternal(scope, 0, null, sink);
    }

    @Override
    public void streamConversation(ChatScopeDTO scope, long conversationId, JsonNode checkpoint, EventSink sink) throws IOException {
        streamInternal(scope, conversationId, checkpoint, sink);
    }

    private void streamInternal(ChatScopeDTO scope, long conversationId, JsonNode checkpoint, EventSink sink) throws IOException {
        if (!slots.tryAcquire()) {
            error(sink, "CHAT_BUSY", "当前问答请求较多，请稍后重试");
            return;
        }
        try {
            run(scope, conversationId, checkpoint, sink);
        } finally {
            slots.release();
        }
    }

    private void run(ChatScopeDTO scope, long conversationId, JsonNode checkpoint, EventSink sink) throws IOException {
        long deadline = System.nanoTime() + properties.timeout().toNanos();
        var payload = new java.util.HashMap<String, Object>(Map.of("namespace", ingestion.namespace(), "libraryId", scope.libraryId(),
                "question", scope.question(), "documents", scope.documents().stream().map(d ->
                        Map.of("documentId", d.documentId(), "ragDocumentId", d.ragDocumentId(), "sha256", d.sha256())).toList()));
        if (conversationId > 0) {
            payload.put("conversationId", conversationId);
            payload.put("checkpoint", checkpoint);
        }
        var request = HttpRequest.newBuilder(ingestion.baseUrl().resolve(conversationId > 0
                        ? "/internal/conversations/chat/stream" : "/internal/chat/stream"))
                .timeout(properties.timeout()).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = pending.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            error(sink, "RAG_TIMEOUT", "连接问答服务超时，请稍后重试");
            return;
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            error(sink, "RAG_STREAM_INTERRUPTED", "问答已中断，请重新提问");
            return;
        } catch (java.util.concurrent.ExecutionException exception) {
            error(sink, "RAG_UNAVAILABLE", "Python 问答服务连接失败，请检查服务状态");
            return;
        }
        var timedOut = new AtomicBoolean();
        try (var input = response.body()) {
            if (response.statusCode() != 200) {
                String code = response.statusCode() == 409 ? "INDEX_SCOPE_MISMATCH" : "RAG_UNAVAILABLE";
                error(sink, code, response.statusCode() == 409 ? "业务文档与索引不一致，请检查文档索引状态" : "Python 问答服务暂不可用");
                return;
            }
            if (!response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
                error(sink, "INVALID_RAG_RESPONSE", "Python 问答响应格式不正确");
                return;
            }
            // HttpRequest.timeout alone does not bound reads after response headers.
            var timer = deadlines.schedule(() -> {
                timedOut.set(true);
                try { input.close(); } catch (IOException ignored) { }
            }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try {
                readEvents(input, scope, conversationId, sink);
            } catch (DownstreamClosed exception) {
                throw exception.original;
            } catch (IOException exception) {
                error(sink, timedOut.get() ? "RAG_TIMEOUT" : "RAG_STREAM_INTERRUPTED",
                        timedOut.get() ? "回答超时，请稍后重试" : "回答连接中断，请重新提问");
            } catch (InvalidStream | tools.jackson.core.JacksonException exception) {
                error(sink, "INVALID_RAG_RESPONSE", "Python 问答响应格式不正确");
            } finally {
                timer.cancel(false);
            }
        }
    }

    private void readEvents(InputStream input, ChatScopeDTO scope, long conversationId, EventSink sink) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String event = "";
        var data = new StringBuilder();
        boolean answered = false;
        boolean checkpointReceived = false;
        int frameLimit = conversationId > 0 ? 8 * 1024 * 1024 : MAX_FRAME;
        int streamLimit = conversationId > 0 ? 16 * 1024 * 1024 : MAX_STREAM;
        int total = 0;
        while (true) {
            String line = boundedLine(reader, frameLimit);
            if (line == null) { throw new IOException("Unexpected stream end"); }
            total += line.length();
            if (total > streamLimit) { throw new InvalidStream(); }
            if (line.isEmpty()) {
                if (data.isEmpty()) { event = ""; continue; }
                var node = json.readTree(data.toString());
                if (!node.isObject()) { throw new InvalidStream(); }
                switch (event) {
                    case "status" -> {
                        String phase = node.path("phase").asString("");
                        if (!Set.of("STARTED", "SEARCHING", "CITING").contains(phase) || answered) { throw new InvalidStream(); }
                        send(sink, event, Map.of("phase", phase));
                    }
                    case "delta" -> {
                        if (!node.path("text").isString() || answered) { throw new InvalidStream(); }
                        send(sink, event, Map.of("text", node.path("text").asString()));
                    }
                    case "answer" -> {
                        if (answered) { throw new InvalidStream(); }
                        send(sink, event, mapAnswer(node, scope));
                        answered = true;
                    }
                    case "error" -> {
                        String code = node.path("code").asString("");
                        if (!Set.of("RAG_TIMEOUT", "RAG_CHAT_FAILED", "CONTEXT_LIMIT_EXCEEDED").contains(code)) { code = "RAG_CHAT_FAILED"; }
                        String message = switch (code) {
                            case "RAG_TIMEOUT" -> "回答超时，请稍后重试";
                            case "CONTEXT_LIMIT_EXCEEDED" -> "会话上下文已达存储上限，请新建会话";
                            default -> "问答处理失败，请检查模型服务后重试";
                        };
                        send(sink, event, Map.of("code", code, "message", message));
                        return;
                    }
                    case "checkpoint" -> {
                        if (conversationId <= 0 || !answered || checkpointReceived || node.path("version").asInt() != 1
                                || node.path("conversationId").asLong() != conversationId
                                || !node.path("messages").isArray() || !node.path("state").isObject()
                                || json.writeValueAsBytes(node).length > 4 * 1024 * 1024) { throw new InvalidStream(); }
                        send(sink, event, node);
                        checkpointReceived = true;
                    }
                    case "done" -> {
                        if (!answered || (conversationId > 0 && !checkpointReceived)
                                || !"COMPLETED".equals(node.path("status").asString())) { throw new InvalidStream(); }
                        send(sink, event, Map.of("status", "COMPLETED"));
                        return;
                    }
                    default -> throw new InvalidStream();
                }
                event = "";
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                event = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).stripLeading()).append('\n');
                if (data.length() > frameLimit) { throw new InvalidStream(); }
            } else if (!line.startsWith(":")) {
                throw new InvalidStream();
            }
        }
    }

    private Object mapAnswer(JsonNode node, ChatScopeDTO scope) {
        String outcome = node.path("outcome").asString("");
        if (!node.path("answer").isString() || node.path("answer").asString().isBlank()
                || !Set.of("ANSWERED", "INSUFFICIENT_EVIDENCE").contains(outcome) || !node.path("citations").isArray()) {
            throw new InvalidStream();
        }
        var allowed = scope.documents().stream().collect(Collectors.toMap(ChatScopeDTO.Document::ragDocumentId, d -> d));
        var citations = new ArrayList<Object>();
        for (var citation : node.path("citations")) {
            var document = allowed.get(citation.path("ragDocumentId").asString(""));
            if (document == null || citation.path("documentId").asLong(-1) != document.documentId()
                    || citation.path("index").asInt(0) <= 0 || citation.path("chunkId").asString("").isBlank()
                    || !citation.path("content").isString() || !citation.path("pageNumbers").isArray()) {
                throw new InvalidStream();
            }
            var pages = new ArrayList<Integer>();
            for (var page : citation.path("pageNumbers")) {
                if (!page.isIntegralNumber() || page.asInt(0) < 1) { throw new InvalidStream(); }
                pages.add(page.asInt());
            }
            citations.add(Map.of("index", citation.path("index").asInt(), "documentId", document.documentId(),
                    "filename", document.filename(), "chunkId", citation.path("chunkId").asString(),
                    "pageNumbers", pages, "content", citation.path("content").asString()));
        }
        if (("ANSWERED".equals(outcome) && citations.isEmpty())
                || ("INSUFFICIENT_EVIDENCE".equals(outcome) && !citations.isEmpty())) { throw new InvalidStream(); }
        return Map.of("answer", node.path("answer").asString(), "outcome", outcome, "citations", citations);
    }

    private String boundedLine(BufferedReader reader, int limit) throws IOException {
        var line = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') { return line.toString(); }
            if (character != '\r') { line.append((char) character); }
            if (line.length() > limit) { throw new InvalidStream(); }
        }
        return line.isEmpty() ? null : line.toString();
    }

    private void send(EventSink sink, String event, Object value) throws IOException {
        try { sink.send(event, value); } catch (IOException exception) { throw new DownstreamClosed(exception); }
    }

    private void error(EventSink sink, String code, String message) throws IOException {
        sink.send("error", Map.of("code", code, "message", message));
    }

    @PreDestroy
    public void close() {
        deadlines.shutdownNow();
        http.shutdownNow();
    }

    private static class InvalidStream extends RuntimeException { }
    private static class DownstreamClosed extends IOException {
        final IOException original;
        DownstreamClosed(IOException original) { this.original = original; }
    }
}
