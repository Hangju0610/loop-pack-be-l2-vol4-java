package com.loopers.support.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 실행 설정.
 * 좋아요 집계 리스너({@link com.loopers.application.like.LikeCountEventListener})가
 * 사용하는 전용 스레드 풀을 정의한다.
 *
 * <p>풀 규모는 기본 HikariCP 풀(10) 안에 들도록 보수적으로 설정한다.
 * 요청 스레드는 TX_like 커밋 후 커넥션을 즉시 반환하므로,
 * 집계 스레드와 커넥션이 겹치지 않아 커넥션 2배 점유 문제가 해소된다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "likeCountExecutor")
    public Executor likeCountExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("like-count-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
