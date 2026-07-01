package com.loopers.application.useractivity;

import com.loopers.domain.useractivity.UserActivityEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class UserActivityLogEventListener {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserActivity(UserActivityEvent event) {
        log.info("user_activity type={} userId={} targetType={} targetId={}",
                event.type(), event.userId(), event.targetType(), event.targetId());
    }
}
