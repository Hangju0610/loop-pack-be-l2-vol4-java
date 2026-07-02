package com.loopers.testconfig;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

@TestConfiguration
public class KafkaTopicsTestConfig {

    @Bean
    NewTopic catalogEventsTopic() {
        return TopicBuilder.name("catalog-events")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic orderEventsTopic() {
        return TopicBuilder.name("order-events")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
