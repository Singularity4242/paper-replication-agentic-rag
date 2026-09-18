package com.example.paperassistant.task;

import com.example.paperassistant.dao.ConversationDAO;
import com.example.paperassistant.service.ConversationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component
@Profile("postgres")
public class ConversationRecoveryWorker {
    private final ConversationDAO dao;
    private final ConversationService service;
    private final java.util.concurrent.ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("conversation-recovery").factory());
    public ConversationRecoveryWorker(ConversationDAO dao, ConversationService service) { this.dao = dao; this.service = service; }
    @PostConstruct
    public void start() {
        executor.scheduleWithFixedDelay(() -> {
            try { for (long id : dao.expiredConversations(100)) { service.recover(id); } }
            catch (Exception exception) { LoggerFactory.getLogger(getClass()).warn("会话恢复暂不可用：{}", exception.getClass().getSimpleName()); }
        }, 1, 30, TimeUnit.SECONDS);
    }
    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
