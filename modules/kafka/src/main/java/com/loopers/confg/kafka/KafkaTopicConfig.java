package com.loopers.confg.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    private static final int EVENT_TOPIC_PARTITIONS = 3;
    private static final int EVENT_TOPIC_REPLICAS = 1;

    @Bean
    public NewTopic catalogEventsTopic() {
        return TopicBuilder.name("catalog-events")
                .partitions(EVENT_TOPIC_PARTITIONS)
                .replicas(EVENT_TOPIC_REPLICAS)
                .build();
    }

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name("order-events")
                .partitions(EVENT_TOPIC_PARTITIONS)
                .replicas(EVENT_TOPIC_REPLICAS)
                .build();
    }

    @Bean
    public NewTopic couponIssueRequestsTopic() {
        return TopicBuilder.name("coupon-issue-requests")
                .partitions(EVENT_TOPIC_PARTITIONS)
                .replicas(EVENT_TOPIC_REPLICAS)
                .build();
    }

    @Bean
    public NewTopic couponIssueRequestsDltTopic() {
        return TopicBuilder.name("coupon-issue-requests.DLT")
                .partitions(EVENT_TOPIC_PARTITIONS)
                .replicas(EVENT_TOPIC_REPLICAS)
                .build();
    }
}
