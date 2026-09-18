package com.example.paperassistant.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("paper.chat")
public record ChatProperties(Duration timeout, int concurrency) {
    public ChatProperties {
        if (timeout == null || timeout.toMillis() < 100 || timeout.toMinutes() > 10
                || concurrency < 1 || concurrency > 16) {
            throw new IllegalArgumentException("问答超时或并发数配置无效");
        }
    }
}
