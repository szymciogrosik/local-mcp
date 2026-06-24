package com.codefromheaven.mcp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SchedulerConfig {
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5); // Allows up to 5 concurrent scheduled tasks
        scheduler.setThreadNamePrefix("mcp-scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
