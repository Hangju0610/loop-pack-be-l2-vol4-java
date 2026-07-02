package com.loopers.domain.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OutboxEvent 도메인 단위 테스트")
class OutboxEventTest {

    @DisplayName("pending() 팩토리 메서드")
    @Nested
    class PendingFactory {

        @DisplayName("[ECP] pending()으로 생성하면 status가 PENDING이고 retryCount가 0이다.")
        @Test
        void createsPendingEvent() {
            // arrange & act
            OutboxEvent event = OutboxEvent.pending("LikeAddedEvent", "catalog-events", "PRD_01");

            // assert
            assertAll(
                    () -> assertNull(event.getId()),
                    () -> assertEquals("LikeAddedEvent", event.getEventType()),
                    () -> assertEquals("catalog-events", event.getTopic()),
                    () -> assertEquals("PRD_01", event.getPartitionKey()),
                    () -> assertEquals(OutboxStatus.PENDING, event.getStatus()),
                    () -> assertEquals(0, event.getRetryCount()),
                    () -> assertNull(event.getPublishedAt())
            );
        }
    }

    @DisplayName("reconstruct() 팩토리 메서드")
    @Nested
    class ReconstructFactory {

        @DisplayName("[ECP] reconstruct()로 기존 데이터를 복원하면 모든 필드가 올바르게 매핑된다.")
        @Test
        void reconstructsEventCorrectly() {
            // arrange
            ZonedDateTime publishedAt = ZonedDateTime.now();

            // act
            OutboxEvent event = OutboxEvent.reconstruct(
                    "OBX_01", "LikeAddedEvent", "{}", "catalog-events", "PRD_01",
                    OutboxStatus.PUBLISHED, 1, publishedAt
            );

            // assert
            assertAll(
                    () -> assertEquals("OBX_01", event.getId()),
                    () -> assertEquals("LikeAddedEvent", event.getEventType()),
                    () -> assertEquals("{}", event.getPayload()),
                    () -> assertEquals(OutboxStatus.PUBLISHED, event.getStatus()),
                    () -> assertEquals(1, event.getRetryCount()),
                    () -> assertEquals(publishedAt, event.getPublishedAt())
            );
        }
    }

    @DisplayName("initAfterSave()")
    @Nested
    class InitAfterSave {

        @DisplayName("[ECP] initAfterSave() 호출 시 id와 payload가 설정된다.")
        @Test
        void setsIdAndPayload() {
            // arrange
            OutboxEvent event = OutboxEvent.pending("LikeAddedEvent", "catalog-events", "PRD_01");

            // act
            event.initAfterSave("OBX_01", "{\"eventId\":\"OBX_01\"}");

            // assert
            assertAll(
                    () -> assertEquals("OBX_01", event.getId()),
                    () -> assertEquals("{\"eventId\":\"OBX_01\"}", event.getPayload())
            );
        }
    }

    @DisplayName("publish() — 상태 전이")
    @Nested
    class Publish {

        @DisplayName("[State Transition] publish() 호출 시 status가 PUBLISHED로 변경되고 publishedAt이 설정된다.")
        @Test
        void transitionsToPublished() {
            // arrange
            OutboxEvent event = OutboxEvent.pending("LikeAddedEvent", "catalog-events", "PRD_01");
            event.initAfterSave("OBX_01", "{}");

            // act
            event.publish();

            // assert
            assertAll(
                    () -> assertEquals(OutboxStatus.PUBLISHED, event.getStatus()),
                    () -> assertNotNull(event.getPublishedAt())
            );
        }
    }

    @DisplayName("fail() — 재시도 및 최대 실패 처리")
    @Nested
    class Fail {

        @DisplayName("[ECP] fail() 1회 호출 시 retryCount가 1 증가하고 status는 PENDING을 유지한다.")
        @Test
        void incrementsRetryCount() {
            // arrange
            OutboxEvent event = OutboxEvent.pending("LikeAddedEvent", "catalog-events", "PRD_01");
            event.initAfterSave("OBX_01", "{}");

            // act
            event.fail();

            // assert
            assertAll(
                    () -> assertEquals(1, event.getRetryCount()),
                    () -> assertEquals(OutboxStatus.PENDING, event.getStatus())
            );
        }

        @DisplayName("[BVA] fail() 3회 호출 시 status가 FAILED로 전이된다.")
        @Test
        void transitionsToFailedAfterMaxRetry() {
            // arrange
            OutboxEvent event = OutboxEvent.pending("LikeAddedEvent", "catalog-events", "PRD_01");
            event.initAfterSave("OBX_01", "{}");

            // act
            event.fail();
            event.fail();
            event.fail();

            // assert
            assertAll(
                    () -> assertEquals(3, event.getRetryCount()),
                    () -> assertEquals(OutboxStatus.FAILED, event.getStatus())
            );
        }

        @DisplayName("[BVA] fail() 2회 시 retryCount는 2이고 status는 아직 PENDING이다.")
        @Test
        void remainsPendingBeforeMaxRetry() {
            // arrange
            OutboxEvent event = OutboxEvent.pending("LikeAddedEvent", "catalog-events", "PRD_01");
            event.initAfterSave("OBX_01", "{}");

            // act
            event.fail();
            event.fail();

            // assert
            assertAll(
                    () -> assertEquals(2, event.getRetryCount()),
                    () -> assertEquals(OutboxStatus.PENDING, event.getStatus())
            );
        }
    }
}
