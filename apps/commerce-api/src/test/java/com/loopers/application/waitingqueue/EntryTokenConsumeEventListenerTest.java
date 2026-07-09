package com.loopers.application.waitingqueue;

import com.loopers.domain.payment.PaymentCompleteEvent;
import com.loopers.domain.waitingqueue.EntryTokenRepository;
import com.loopers.domain.waitingqueue.EntryTokenVO;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@DisplayName("EntryTokenConsumeEventListener 통합 테스트")
class EntryTokenConsumeEventListenerTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntryTokenRepository entryTokenRepository;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    @Nested
    @DisplayName("onPaymentSucceeded")
    class OnPaymentSucceeded {

        @Test
        @DisplayName("결제 완료 이벤트가 커밋되면 해당 유저의 Entry-Token이 삭제된다")
        void deletes_entry_token_when_payment_complete_event_is_committed() {

            entryTokenRepository.save(EntryTokenVO.create("user-1"));

            transactionTemplate.executeWithoutResult(status ->
                    eventPublisher.publishEvent(new PaymentCompleteEvent("user-1", "ORD_1")));

            await().atMost(5, SECONDS).untilAsserted(() ->
                    assertThat(entryTokenRepository.find("user-1")).isEmpty());
        }

        @Test
        @DisplayName("트랜잭션이 롤백되면 토큰을 삭제하지 않는다 (AFTER_COMMIT)")
        void does_not_delete_token_when_transaction_is_rolled_back() {

            entryTokenRepository.save(EntryTokenVO.create("user-1"));

            transactionTemplate.executeWithoutResult(status -> {
                eventPublisher.publishEvent(new PaymentCompleteEvent("user-1", "ORD_1"));
                status.setRollbackOnly();
            });

            await().during(500, MILLISECONDS).atMost(2, SECONDS).untilAsserted(() ->
                    assertThat(entryTokenRepository.find("user-1")).isPresent());
        }
    }
}
