package com.example.paperassistant.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Profile("postgres")
public class ChatAsyncConfiguration {
    @Bean(destroyMethod = "close")
    public ExecutorService chatStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public WebMvcConfigurer chatAsyncSupport(ChatProperties properties, ExecutorService chatStreamExecutor) {
        return new WebMvcConfigurer() {
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setTaskExecutor(new TaskExecutorAdapter(chatStreamExecutor));
                configurer.setDefaultTimeout(properties.timeout().plusSeconds(10).toMillis());
            }
        };
    }
}
