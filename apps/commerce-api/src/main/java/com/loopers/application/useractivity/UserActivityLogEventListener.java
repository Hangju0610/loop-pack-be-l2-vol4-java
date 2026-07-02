package com.loopers.application.useractivity;

import com.loopers.domain.like.LikeAddedEvent;
import com.loopers.domain.like.LikeRemovedEvent;
import com.loopers.domain.order.OrderCreatedEvent;
import com.loopers.domain.payment.PaymentCompleteEvent;
import com.loopers.domain.payment.PaymentFailedEvent;
import com.loopers.domain.product.ProductViewedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class UserActivityLogEventListener {

    private static final String ANONYMOUS = "ANONYMOUS";
    private static final String PRODUCT_TARGET_TYPE = "PRODUCT";
    private static final String ORDER_TARGET_TYPE = "ORDER";

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onProductViewed(ProductViewedEvent event) {
        String userId = event.userId() != null ? event.userId() : ANONYMOUS;
        log.info("user_activity type=PRODUCT_VIEW userId={} targetType={} targetId={}",
                userId, PRODUCT_TARGET_TYPE, event.productId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLikeAdded(LikeAddedEvent event) {
        log.info("user_activity type=PRODUCT_LIKE userId={} targetType={} targetId={}",
                event.userId(), PRODUCT_TARGET_TYPE, event.productId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLikeRemoved(LikeRemovedEvent event) {
        log.info("user_activity type=PRODUCT_LIKE_REMOVE userId={} targetType={} targetId={}",
                event.userId(), PRODUCT_TARGET_TYPE, event.productId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("user_activity type=ORDER_CREATED userId={} targetType={} targetId={}",
                event.userId(), ORDER_TARGET_TYPE, event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPaymentComplete(PaymentCompleteEvent event) {
        log.info("user_activity type=PAYMENT_COMPLETE userId={} targetType={} targetId={}",
                event.userId(), ORDER_TARGET_TYPE, event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPaymentFailed(PaymentFailedEvent event) {
        log.info("user_activity type=PAYMENT_FAILED userId={} targetType={} targetId={}",
                event.userId(), ORDER_TARGET_TYPE, event.orderId());
    }
}
