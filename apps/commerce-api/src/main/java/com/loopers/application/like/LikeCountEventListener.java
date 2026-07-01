package com.loopers.application.like;

import com.loopers.domain.like.LikeAddedEvent;
import com.loopers.domain.like.LikeRemovedEvent;
import com.loopers.domain.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 좋아요 이벤트를 수신해 상품 집계치(like_count)를 갱신하는 전담 컴포넌트.
 * 좋아요 트랜잭션(TX_like) 커밋 후(AFTER_COMMIT) 실행되며,
 * 자체 트랜잭션(REQUIRES_NEW)에서 집계를 수행한다.
 * 집계 실패는 로그로 기록하고 삼켜 좋아요 커밋에 영향을 주지 않는다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class LikeCountEventListener {

    private final ProductRepository productRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLikeAdded(LikeAddedEvent event) {
        try {
            productRepository.incrementLikeCount(event.productId());
        } catch (Exception e) {
            log.error("좋아요 집계 증가 실패 [productId={}]", event.productId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLikeRemoved(LikeRemovedEvent event) {
        try {
            productRepository.decrementLikeCount(event.productId());
        } catch (Exception e) {
            log.error("좋아요 집계 감소 실패 [productId={}]", event.productId(), e);
        }
    }
}
