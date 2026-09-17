package com.example.paperassistant.task;

import com.example.paperassistant.client.RagClient;
import com.example.paperassistant.client.RagClientException;
import com.example.paperassistant.config.IngestionProperties;
import com.example.paperassistant.dao.DocumentDAO;
import com.example.paperassistant.service.IngestionTaskService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 每个线程一次只领取一个任务；数据库是队列，不在内存积压任务。 */
@Component
@Profile("postgres")
@ConditionalOnProperty(name = "paper.ingestion.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentIngestionWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionWorker.class);
    private final IngestionTaskService tasks;
    private final DocumentDAO documents;
    private final RagClient client;
    private final IngestionProperties properties;
    private ScheduledExecutorService executor;

    public DocumentIngestionWorker(IngestionTaskService tasks, DocumentDAO documents, RagClient client,
                                   IngestionProperties properties) {
        this.tasks = tasks;
        this.documents = documents;
        this.client = client;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        executor = Executors.newScheduledThreadPool(properties.concurrency(),
                Thread.ofPlatform().name("document-ingestion-", 0).factory());
        for (int i = 0; i < properties.concurrency(); i++) {
            executor.scheduleWithFixedDelay(this::runOnce, properties.pollInterval().toMillis(),
                    properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    public void runOnce() {
        try {
            tasks.claim().ifPresent(task -> {
                try {
                    var document = documents.findById(task.documentId()).orElseThrow();
                    tasks.complete(task, client.ingest(document));
                } catch (RagClientException error) {
                    tasks.fail(task, error);
                } catch (Exception error) {
                    // 不输出上游原始响应；执行结果无法落库时由租约恢复。
                    LOGGER.error("索引任务执行异常，taskId={}，类型={}", task.id(), error.getClass().getSimpleName());
                    tasks.fail(task, new RagClientException("IMPORT_FAILED", "处理异常，请重试", true));
                }
            });
        } catch (Exception error) {
            LOGGER.error("索引任务领取或状态更新失败，类型={}", error.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void stop() {
        if (executor != null) { executor.shutdownNow(); }
    }
}
