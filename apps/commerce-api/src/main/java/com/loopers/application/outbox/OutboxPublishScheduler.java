package com.loopers.application.outbox;

import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
@Component
public class OutboxPublishScheduler {

    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<Object, Object> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findPending(BATCH_SIZE);
        for (OutboxEvent event : pending) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayload()).get();
                event.publish();
            } catch (Exception e) {
                log.warn("Outbox 이벤트 발행 실패 [id={}, topic={}, retryCount={}]",
                        event.getId(), event.getTopic(), event.getRetryCount(), e);
                event.fail();
            }
            outboxEventRepository.update(event);
        }
    }
}
