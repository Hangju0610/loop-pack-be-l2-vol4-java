package com.loopers.application.ranking;

import com.loopers.domain.ranking.RankingScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Slf4j
@RequiredArgsConstructor
@Component
public class RankingCarryOverScheduler {

    static final double CARRY_OVER_WEIGHT = 0.1;
    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");

    private final RankingScoreRepository rankingScoreRepository;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void carryOverDaily() {
        carryOver(LocalDate.now(ZONE_SEOUL));
    }

    void carryOver(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        if (!rankingScoreRepository.hasRanking(yesterday)) {
            log.info("전일 랭킹 키가 없어 이월을 건너뜁니다 [date={}]", yesterday);
            return;
        }
        if (!rankingScoreRepository.tryMarkCarryOverDone(today)) {
            log.info("이미 이월이 완료되어 건너뜁니다 [date={}]", today);
            return;
        }
        rankingScoreRepository.carryOver(yesterday, today, CARRY_OVER_WEIGHT);
        log.info("전일 랭킹 이월 완료 [from={}, to={}, weight={}]", yesterday, today, CARRY_OVER_WEIGHT);
    }
}
