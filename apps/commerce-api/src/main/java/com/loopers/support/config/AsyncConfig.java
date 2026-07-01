package com.loopers.support.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 비동기 실행 설정.
 * 좋아요 집계 리스너({@link com.loopers.application.like.LikeCountEventListener})와
 * 주문 결제 이벤트 리스너({@link com.loopers.application.order.OrderPaymentEventListener})가
 * 사용하는 전용 스레드 풀을 정의한다.
 *
 * <p>풀 규모는 기본 HikariCP 풀(10) 안에 들도록 보수적으로 설정한다.
 * 요청 스레드는 TX 커밋 후 커넥션을 즉시 반환하므로,
 * 비동기 스레드와 커넥션이 겹치지 않아 커넥션 2배 점유 문제가 해소된다.
 *
 * <p>포화 시 거부 정책은 <b>로그 후 폐기</b>다. {@code CallerRunsPolicy}를 쓰면
 * 거부된 태스크가 AFTER_COMMIT 시점의 요청 스레드에서 인라인 실행되어, 원 요청 커넥션과 겹치는
 * 커넥션 2배 점유·요청 지연이 정확히 재발한다(이 풀이 없애려던 리스크).
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

    /**
     * 주문 결제 이벤트 전용 풀.
     * 포화 시 {@code CallerRunsPolicy}를 사용한다. 주문 완료/보상은 유실이 허용되지 않으므로
     * 요청 스레드 지연을 감수하더라도 태스크를 버리지 않는다.
     * (like 집계와 달리 근사치 허용 불가 — 유실 시 주문이 영구 오염됨)
     */
    @Bean(name = "orderEventExecutor")
    public Executor orderEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("order-event-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
