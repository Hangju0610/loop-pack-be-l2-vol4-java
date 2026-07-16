package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.RankingItem;
import com.loopers.domain.ranking.RankingRepository;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RankingRepositoryImplTest {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private RankingRepository rankingRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    private void seed(LocalDate date, String productId, double score) {
        redisTemplate.opsForZSet().add("ranking:all:" + date.format(DATE_FORMAT), productId, score);
    }

    @DisplayName("findPage")
    @Nested
    class FindPage {

        @DisplayName("[ECP] score 내림차순으로 정렬된 RankingItem 목록을 rank와 함께 반환한다.")
        @Test
        void returnsItemsOrderedByScoreDesc_withRank() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);
            seed(date, "PRD_2", 300.0);
            seed(date, "PRD_3", 200.0);

            // act
            List<RankingItem> result = rankingRepository.findPage(date, 0, 10);

            // assert
            assertThat(result).hasSize(3);
            assertThat(result.get(0).productId()).isEqualTo("PRD_2");
            assertThat(result.get(0).rank()).isEqualTo(1);
            assertThat(result.get(1).productId()).isEqualTo("PRD_3");
            assertThat(result.get(1).rank()).isEqualTo(2);
            assertThat(result.get(2).productId()).isEqualTo("PRD_1");
            assertThat(result.get(2).rank()).isEqualTo(3);
        }

        @DisplayName("[Boundary] offset 이후의 rank는 offset+index+1로 계산된다.")
        @Test
        void calculatesRankFromOffset() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);
            seed(date, "PRD_2", 300.0);
            seed(date, "PRD_3", 200.0);

            // act
            List<RankingItem> result = rankingRepository.findPage(date, 1, 10);

            // assert
            assertThat(result).hasSize(2);
            assertThat(result.get(0).productId()).isEqualTo("PRD_3");
            assertThat(result.get(0).rank()).isEqualTo(2);
            assertThat(result.get(1).productId()).isEqualTo("PRD_1");
            assertThat(result.get(1).rank()).isEqualTo(3);
        }

        @DisplayName("[Boundary] 존재하지 않는 키를 조회하면 빈 목록을 반환한다.")
        @Test
        void returnsEmptyList_whenKeyDoesNotExist() {
            // act
            List<RankingItem> result = rankingRepository.findPage(LocalDate.of(2099, 1, 1), 0, 10);

            // assert
            assertThat(result).isEmpty();
        }
    }

    @DisplayName("countByDate")
    @Nested
    class CountByDate {

        @DisplayName("[ECP] 등록된 멤버 수를 반환한다.")
        @Test
        void returnsMemberCount() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);
            seed(date, "PRD_2", 300.0);

            // act & assert
            assertThat(rankingRepository.countByDate(date)).isEqualTo(2);
        }

        @DisplayName("[Boundary] 존재하지 않는 키는 0을 반환한다.")
        @Test
        void returnsZero_whenKeyDoesNotExist() {
            // act & assert
            assertThat(rankingRepository.countByDate(LocalDate.of(2099, 1, 1))).isEqualTo(0);
        }
    }

    @DisplayName("findRank")
    @Nested
    class FindRank {

        @DisplayName("[ECP] 1위 상품의 rank는 1이다.")
        @Test
        void returnsOne_forTopRankedProduct() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);
            seed(date, "PRD_2", 300.0);

            // act & assert
            assertThat(rankingRepository.findRank(date, "PRD_2")).contains(1L);
        }

        @DisplayName("[ECP] 중간 순위 상품의 rank를 반환한다.")
        @Test
        void returnsRank_forMidRankedProduct() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);
            seed(date, "PRD_2", 300.0);
            seed(date, "PRD_3", 200.0);

            // act & assert
            assertThat(rankingRepository.findRank(date, "PRD_3")).contains(2L);
        }

        @DisplayName("[Boundary] ZSET에 없는 상품은 empty를 반환한다.")
        @Test
        void returnsEmpty_whenProductIsNotInZSet() {
            // arrange
            LocalDate date = LocalDate.of(2026, 7, 16);
            seed(date, "PRD_1", 100.0);

            // act & assert
            assertThat(rankingRepository.findRank(date, "PRD_999")).isEmpty();
        }

        @DisplayName("[Boundary] 존재하지 않는 키는 empty를 반환한다.")
        @Test
        void returnsEmpty_whenKeyDoesNotExist() {
            // act & assert
            assertThat(rankingRepository.findRank(LocalDate.of(2099, 1, 1), "PRD_1")).isEmpty();
        }
    }
}
