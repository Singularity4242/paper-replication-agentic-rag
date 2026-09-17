package com.example.paperassistant.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("paper.ingestion")
public record IngestionProperties(URI baseUrl, String namespace, int concurrency, int maxAttempts,
        Duration pollInterval, Duration requestTimeout, Duration leaseDuration, Duration retryDelay) {
    public IngestionProperties {
        if (baseUrl == null || !java.util.Set.of("http", "https").contains(baseUrl.getScheme())
                || baseUrl.getHost() == null || namespace == null || !namespace.matches("[a-zA-Z0-9_-]{1,64}")
                || concurrency < 1 || concurrency > 8 || maxAttempts < 1 || maxAttempts > 10
                || pollInterval == null || pollInterval.toMillis() < 100
                || requestTimeout == null || requestTimeout.toSeconds() < 1
                || leaseDuration == null || leaseDuration.compareTo(requestTimeout.plusSeconds(30)) < 0
                || retryDelay == null || retryDelay.toMillis() < 100) {
            throw new IllegalArgumentException("索引配置无效；租约必须至少比 HTTP 超时长 30 秒");
        }
    }
}
