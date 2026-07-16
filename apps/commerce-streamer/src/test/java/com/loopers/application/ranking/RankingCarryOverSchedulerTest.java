package com.loopers.application.ranking;

import com.loopers.domain.ranking.RankingScoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RankingCarryOverScheduler 단위 테스트")
class RankingCarryOverSchedulerTest {

    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");

    @Mock
    private RankingScoreRepository rankingScoreRepository;

    @DisplayName("carryOverDaily는 23시 55분에 실행된다.")
    @Test
    void isScheduledAt2355() throws NoSuchMethodException {
        Method carryOverDailyMethod = RankingCarryOverScheduler.class.getDeclaredMethod("carryOverDaily");

        Scheduled scheduled = carryOverDailyMethod.getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("0 55 23 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }

    @DisplayName("carryOverDaily는 다음 일자의 랭킹을 미리 이월한다.")
    @Test
    void carriesOverTomorrowRanking_whenCarryOverDailyCalled() {
        LocalDate today = LocalDate.of(2026, 7, 17);
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T14:55:00Z"), ZONE_SEOUL);
        RankingCarryOverScheduler scheduler = new RankingCarryOverScheduler(rankingScoreRepository, clock);

        scheduler.carryOverDaily();

        verify(rankingScoreRepository).hasRanking(today);
    }
}
