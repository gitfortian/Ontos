package com.ontos.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务装配（M2/W4/T4.4）。
 *
 * <p>当前只有一类异步动作：清单激活后回收旧版本缓存键（ADR-0008 决策 4 要求「异步删除」）。
 * 用固定小池而不是默认的 SimpleAsyncTaskExecutor：后者每次调用新建线程，发布频率虽低也不该无界。
 * 拒绝策略选 CallerRuns：极端饱和时退化为同步执行，宁可发布慢一点，也不静默丢掉空间回收。
 */
@Configuration
@EnableAsync
public class AsyncConfiguration {

    @Bean("cacheInvalidationExecutor")
    public ThreadPoolTaskExecutor cacheInvalidationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("cache-invalidation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

}
