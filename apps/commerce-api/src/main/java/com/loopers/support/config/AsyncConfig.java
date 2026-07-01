package com.loopers.support.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 비동기 실행 설정.
 * 좋아요 집계 리스너({@link com.loopers.application.like.LikeCountEventListener})가
 * 사용하는 전용 스레드 풀을 정의한다.
 *
 * <p>풀 규모는 기본 HikariCP 풀(10) 안에 들도록 보수적으로 설정한다.
 * 요청 스레드는 TX_like 커밋 후 커넥션을 즉시 반환하므로,
 * 집계 스레드와 커넥션이 겹치지 않아 커넥션 2배 점유 문제가 해소된다.
 *
 * <p>포화(큐 100 + max 4 소진) 시 거부 정책은 <b>로그 후 폐기</b>다. {@code CallerRunsPolicy}를 쓰면
 * 거부된 집계가 AFTER_COMMIT 시점의 요청 스레드에서 인라인 실행되어, 원 요청 커넥션과 겹치는
 * 커넥션 2배 점유·요청 지연이 정확히 재발한다(이 풀이 없애려던 리스크). 집계는 유실 허용(근사 집계치,
 * ADR-038)이므로 요청 스레드를 보호하기 위해 거부 태스크는 경고 로그만 남기고 버린다.
 * 유실된 delta는 재계산 배치(ADR-038 후속)로 교정한다.
 */
@Slf4j
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
        executor.setRejectedExecutionHandler((rejected, exec) ->
                log.warn("좋아요 집계 태스크 거부됨 — 풀 포화로 delta 유실 (재계산 배치로 교정 예정)"));
        executor.initialize();
        return executor;
    }
}
