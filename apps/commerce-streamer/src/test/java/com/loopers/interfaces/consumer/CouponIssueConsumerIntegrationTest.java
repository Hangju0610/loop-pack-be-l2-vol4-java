package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.infrastructure.EntityId;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "spring.kafka.consumer.auto-offset-reset=earliest")
@EmbeddedKafka(
        partitions = 3,
        topics = {"catalog-events", "order-events", "demo.internal.topic-v1", "coupon-issue-requests", "coupon-issue-requests.DLT"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Import({MySqlTestContainersConfig.class, RedisTestContainersConfig.class})
@DisplayName("CouponIssueConsumer 통합 테스트")
class CouponIssueConsumerIntegrationTest {

    private static final String TOPIC = "coupon-issue-requests";
    private static final String FAKE_USER_ID = "USR_FAKE_001";

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("[T1] 정상 발급 이벤트 수신 시 쿠폰 생성, issuedCount 증가, 요청 SUCCESS 처리된다.")
    @Nested
    class NormalIssuance {

        @Test
        void createsCoupon_andUpdatesIssuedCount_whenIssueRequestEventReceived() throws Exception {
            // arrange
            String templateId = insertCouponTemplate(10L, 5L);
            String requestId = insertCouponIssueRequest(FAKE_USER_ID, templateId, "PENDING");
            String payload = buildPayload(
                    EntityId.generate("OBX"), "CouponIssueRequestedEvent",
                    Map.of("requestId", requestId, "userId", FAKE_USER_ID, "couponTemplateId", templateId)
            );

            // act
            kafkaTemplate.send(TOPIC, templateId, payload);

            // assert
            await().atMost(10, SECONDS).untilAsserted(() -> {
                Integer couponCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupons WHERE ref_user_id = ? AND ref_coupon_template_id = ?",
                        Integer.class, FAKE_USER_ID, templateId);
                Long issuedCount = jdbcTemplate.queryForObject(
                        "SELECT issued_count FROM coupon_templates WHERE id = ?",
                        Long.class, templateId);
                String status = jdbcTemplate.queryForObject(
                        "SELECT status FROM coupon_issue_requests WHERE id = ?",
                        String.class, requestId);

                assertEquals(1, couponCount, "쿠폰이 1개 생성되어야 한다");
                assertEquals(6L, issuedCount, "issuedCount가 1 증가해야 한다");
                assertEquals("SUCCESS", status, "요청 상태가 SUCCESS여야 한다");
            });
        }
    }

    @DisplayName("[T2] 동일 이벤트 중복 수신 시 쿠폰 중복 생성 없이 SUCCESS 처리된다 (멱등성).")
    @Nested
    class IdempotentIssuance {

        @Test
        void doesNotDuplicateCoupon_whenSameEventReceivedTwice() throws Exception {
            // arrange
            String templateId = insertCouponTemplate(10L, 5L);
            String requestId = insertCouponIssueRequest(FAKE_USER_ID, templateId, "PENDING");
            String eventId = EntityId.generate("OBX");
            String payload = buildPayload(
                    eventId, "CouponIssueRequestedEvent",
                    Map.of("requestId", requestId, "userId", FAKE_USER_ID, "couponTemplateId", templateId)
            );

            // act — 동일 eventId 두 번 전송
            kafkaTemplate.send(TOPIC, templateId, payload);
            kafkaTemplate.send(TOPIC, templateId, payload);

            // assert — 멱등 처리로 쿠폰은 1개, issuedCount는 1만 증가
            await().atMost(10, SECONDS).untilAsserted(() -> {
                Integer couponCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupons WHERE ref_user_id = ? AND ref_coupon_template_id = ?",
                        Integer.class, FAKE_USER_ID, templateId);
                Long issuedCount = jdbcTemplate.queryForObject(
                        "SELECT issued_count FROM coupon_templates WHERE id = ?",
                        Long.class, templateId);
                String status = jdbcTemplate.queryForObject(
                        "SELECT status FROM coupon_issue_requests WHERE id = ?",
                        String.class, requestId);

                assertEquals(1, couponCount, "쿠폰이 중복 생성되면 안 된다");
                assertEquals(6L, issuedCount, "issuedCount가 1만 증가해야 한다");
                assertEquals("SUCCESS", status, "요청 상태가 SUCCESS여야 한다");
            });
        }
    }

    @DisplayName("[T3] 수량 초과 시 요청 FAILED 처리 후 정상 종료된다 (비즈니스 실패, DLT 미전송).")
    @Nested
    class CapacityExceeded {

        @Test
        void marksRequestAsFailed_whenTemplateIsAtCapacity() throws Exception {
            // arrange — maxIssueCount=5, issuedCount=5 (꽉 찬 상태)
            String templateId = insertCouponTemplate(5L, 5L);
            String requestId = insertCouponIssueRequest(FAKE_USER_ID, templateId, "PENDING");
            String payload = buildPayload(
                    EntityId.generate("OBX"), "CouponIssueRequestedEvent",
                    Map.of("requestId", requestId, "userId", FAKE_USER_ID, "couponTemplateId", templateId)
            );

            // act
            kafkaTemplate.send(TOPIC, templateId, payload);

            // assert — 쿠폰 미생성, request=FAILED
            await().atMost(10, SECONDS).untilAsserted(() -> {
                Integer couponCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM coupons WHERE ref_user_id = ? AND ref_coupon_template_id = ?",
                        Integer.class, FAKE_USER_ID, templateId);
                Long issuedCount = jdbcTemplate.queryForObject(
                        "SELECT issued_count FROM coupon_templates WHERE id = ?",
                        Long.class, templateId);
                String status = jdbcTemplate.queryForObject(
                        "SELECT status FROM coupon_issue_requests WHERE id = ?",
                        String.class, requestId);

                assertEquals(0, couponCount, "쿠폰이 생성되면 안 된다");
                assertEquals(5L, issuedCount, "issuedCount가 변하면 안 된다");
                assertEquals("FAILED", status, "요청 상태가 FAILED여야 한다");
            });
        }
    }

    private String insertCouponTemplate(Long maxIssueCount, Long issuedCount) {
        String id = EntityId.generate("CTP");
        jdbcTemplate.update("""
                INSERT INTO coupon_templates (id, name, type, value, expired_at, max_issue_count, issued_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(6), NOW(6))
                """,
                id, "테스트 쿠폰", "FIXED", 1000L,
                java.sql.Timestamp.from(ZonedDateTime.now().plusDays(30).toInstant()),
                maxIssueCount, issuedCount
        );
        return id;
    }

    private String insertCouponIssueRequest(String userId, String couponTemplateId, String status) {
        String id = EntityId.generate("CIR");
        jdbcTemplate.update("""
                INSERT INTO coupon_issue_requests (id, ref_user_id, ref_coupon_template_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(6), NOW(6))
                """,
                id, userId, couponTemplateId, status
        );
        return id;
    }

    private String buildPayload(String eventId, String eventType, Map<String, Object> data) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("occurredAt", ZonedDateTime.now().toString());
        envelope.put("data", data);
        return objectMapper.writeValueAsString(envelope);
    }
}
