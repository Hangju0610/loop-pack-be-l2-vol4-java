package com.loopers.application.useractivity;

import com.loopers.domain.like.LikeAddedEvent;
import com.loopers.domain.order.OrderCreatedEvent;
import com.loopers.domain.useractivity.UserActivityEvent;
import com.loopers.domain.useractivity.UserActivityType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class UserActivityLogEventListener {

    private static final String PRODUCT_TARGET_TYPE = "PRODUCT";
    private static final String ORDER_TARGET_TYPE = "ORDER";

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserActivity(UserActivityEvent event) {
        logUserActivity(event.type(), event.userId(), event.targetType(), event.targetId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLikeAdded(LikeAddedEvent event) {
        logUserActivity(UserActivityType.PRODUCT_LIKE, event.userId(), PRODUCT_TARGET_TYPE, event.productId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        logUserActivity(UserActivityType.ORDER_CREATED, event.userId(), ORDER_TARGET_TYPE, event.orderId());
    }

    private void logUserActivity(UserActivityType type, String userId, String targetType, String targetId) {
        log.info("user_activity type={} userId={} targetType={} targetId={}",
                type, userId, targetType, targetId);
    }
}
