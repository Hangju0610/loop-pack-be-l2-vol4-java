package com.loopers.application.like;

import com.loopers.domain.like.LikeAddedEvent;
import com.loopers.domain.like.LikeRemovedEvent;
import com.loopers.domain.product.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 좋아요 이벤트를 수신해 상품 집계치(like_count)를 갱신하는 전담 컴포넌트.
 * 좋아요 트랜잭션(TX_like) 커밋 후(AFTER_COMMIT) 전용 비동기 스레드 풀에서 실행되며,
 * 자체 트랜잭션(REQUIRES_NEW)에서 집계를 수행한다.
 *
 * <p>집계 트랜잭션은 {@link TransactionTemplate}(프로그래밍적)으로 연다. 선언적 {@code @Transactional}을
 * 리스너 메서드에 붙이면 트랜잭션 commit이 메서드 body 바깥에서 일어나 try-catch로 잡히지 않으므로,
 * commit 단계 실패(커넥션 드롭·lock timeout 등)까지 catch 범위에 넣기 위해 프로그래밍적 트랜잭션을 사용한다.
 *
 * <p>{@code @Async("likeCountExecutor")}로 요청 스레드와 분리 실행되어,
 * TX_like 커밋 후 요청 커넥션이 즉시 풀에 반환된다(커넥션 2배 점유 해소).
 * 집계 실패는 로그로 기록하고 삼켜 이미 커밋된 좋아요에 영향을 주지 않는다.
 */
@Slf4j
@Component
public class LikeCountEventListener {

    private final ProductRepository productRepository;
    private final TransactionTemplate transactionTemplate;

    public LikeCountEventListener(ProductRepository productRepository,
            PlatformTransactionManager transactionManager) {
        this.productRepository = productRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Async("likeCountExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLikeAdded(LikeAddedEvent event) {
        try {
            transactionTemplate.executeWithoutResult(status -> productRepository.incrementLikeCount(event.productId()));
        } catch (Exception e) {
            log.error("좋아요 집계 증가 실패 [productId={}]", event.productId(), e);
        }
    }

    @Async("likeCountExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLikeRemoved(LikeRemovedEvent event) {
        try {
            transactionTemplate.executeWithoutResult(status -> productRepository.decrementLikeCount(event.productId()));
        } catch (Exception e) {
            log.error("좋아요 집계 감소 실패 [productId={}]", event.productId(), e);
        }
    }
}
