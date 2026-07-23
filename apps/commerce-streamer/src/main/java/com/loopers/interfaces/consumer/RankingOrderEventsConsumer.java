package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.domain.ranking.RankingScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Component
public class RankingOrderEventsConsumer {

    private final RankingEventProcessor rankingEventProcessor;
    private final RankingScoreRepository rankingScoreRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${commerce-streamer.kafka.topics.order-events:order-events}",
            groupId = RankingEventProcessor.ORDER_CONSUMER_GROUP,
            containerFactory = KafkaConfig.BATCH_LISTENER
    )
    public void consumeOrderEvents(
            List<ConsumerRecord<Object, Object>> messages,
            Acknowledgment acknowledgment
    ) {
        List<OutboxEventPayload> payloads = parse(messages);
        Map<LocalDate, Map<String, Double>> deltas = rankingEventProcessor.collectOrderDeltas(payloads);
        deltas.forEach(rankingScoreRepository::incrementScores);
        acknowledgment.acknowledge();
    }

    private List<OutboxEventPayload> parse(List<ConsumerRecord<Object, Object>> messages) {
        List<OutboxEventPayload> payloads = new ArrayList<>();
        for (ConsumerRecord<Object, Object> record : messages) {
            try {
                payloads.add(OutboxEventPayload.from(record.value(), objectMapper));
            } catch (Exception e) {
                log.error("order-events 파싱 실패 [offset={}]", record.offset(), e);
                throw new IllegalStateException("order-events 파싱 실패", e);
            }
        }
        return payloads;
    }
}
