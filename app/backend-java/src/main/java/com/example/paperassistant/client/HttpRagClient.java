package com.example.paperassistant.client;

import com.example.paperassistant.config.DocumentStorageProperties;
import com.example.paperassistant.config.IngestionProperties;
import com.example.paperassistant.model.dataobject.DocumentDO;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("postgres")
@EnableConfigurationProperties(IngestionProperties.class)
public class HttpRagClient implements RagClient {
    private final IngestionProperties properties;
    private final DocumentStorageProperties storage;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpRagClient(IngestionProperties properties, DocumentStorageProperties storage) {
        this.properties = properties;
        this.storage = storage;
    }

    @Override
    public Result ingest(DocumentDO document) {
        var path = storage.root().resolve(document.storageKey()).normalize();
        if (!path.getParent().equals(storage.root()) || !Files.isRegularFile(path)) {
            throw new RagClientException("SOURCE_MISSING", "原文件不存在，请检查文件存储目录", false);
        }
        String boundary = "rag-" + UUID.randomUUID();
        var parts = new ArrayList<HttpRequest.BodyPublisher>();
        Map<String, String> fields = Map.of("documentId", document.id().toString(),
                "libraryId", Long.toString(document.libraryId()), "sha256", document.sha256(),
                "namespace", properties.namespace(), "originalFilename", document.originalFilename());
        fields.forEach((key, value) -> parts.add(HttpRequest.BodyPublishers.ofString(
                "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + key
                        + "\"\r\n\r\n" + value + "\r\n", StandardCharsets.UTF_8)));
        try {
            parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary
                    + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"upload\""
                    + "\r\nContent-Type: application/octet-stream\r\n\r\n"));
            parts.add(HttpRequest.BodyPublishers.ofFile(path));
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
            var request = HttpRequest.newBuilder(properties.baseUrl().resolve("/internal/documents/ingest"))
                    .timeout(properties.requestTimeout()).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            {
                byte[] bytes = response.body();
                if (response.statusCode() != 200) {
                    boolean retryable = response.statusCode() >= 500 || response.statusCode() == 429;
                    throw new RagClientException("RAG_HTTP_ERROR", "Python 导入服务返回错误，请检查服务状态", retryable);
                }
                if (bytes.length > 65536) {
                    throw invalidResponse();
                }
                var body = json.readTree(bytes);
                if (body.path("documentId").asLong(-1) != document.id()
                        || body.path("libraryId").asLong(-1) != document.libraryId()
                        || !document.sha256().equals(body.path("sha256").asString())) {
                    throw invalidResponse();
                }
                String status = body.path("status").asString();
                if ("INDEXED".equals(status) && !body.path("ragDocumentId").asString("").isBlank()) {
                    return new Result(status, body.path("ragDocumentId").asString());
                }
                if ("UNSUPPORTED".equals(status)) {
                    return new Result(status, null);
                }
                if ("FAILED".equals(status)) {
                    String code = body.path("errorCode").asString("");
                    boolean retryable = body.path("retryable").asBoolean(false);
                    String safeCode = java.util.Set.of("PARSING_FAILED", "EMPTY_CONTENT", "MODEL_UNAVAILABLE", "IMPORT_FAILED", "HASH_MISMATCH")
                            .contains(code) ? code : "IMPORT_FAILED";
                    throw new RagClientException(safeCode, "资料处理失败，请检查解析器或模型服务后重试", retryable);
                }
                throw invalidResponse();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RagClientException("WORKER_INTERRUPTED", "处理被中断，将重试", true);
        } catch (IOException exception) {
            throw new RagClientException("RAG_UNAVAILABLE", "Python 服务连接失败或超时，将重试", true);
        } catch (tools.jackson.core.JacksonException exception) {
            throw invalidResponse();
        }
    }

    private RagClientException invalidResponse() {
        return new RagClientException("INVALID_RAG_RESPONSE", "Python 服务响应格式不正确", true);
    }
}
