package com.loopers.application.waitingqueue;

import com.loopers.domain.waitingqueue.EntryTokenRepository;
import com.loopers.domain.waitingqueue.EntryTokenVO;
import com.loopers.domain.waitingqueue.EstimatedWaitPolicy;
import com.loopers.domain.waitingqueue.WaitingQueueEntryVO;
import com.loopers.domain.waitingqueue.WaitingQueueRankCalculator;
import com.loopers.domain.waitingqueue.WaitingQueueRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class WaitingQueueApplicationService {

    private static final int PUBLISH_BATCH_SIZE = 20;

    private final WaitingQueueRepository waitingQueueRepository;
    private final EntryTokenRepository entryTokenRepository;
    private final WaitingQueueRankCalculator rankCalculator = new WaitingQueueRankCalculator();
    private final EstimatedWaitPolicy estimatedWaitPolicy = new EstimatedWaitPolicy();

    public WaitingQueueApplicationService(
            WaitingQueueRepository waitingQueueRepository,
            EntryTokenRepository entryTokenRepository
    ) {
        this.waitingQueueRepository = waitingQueueRepository;
        this.entryTokenRepository = entryTokenRepository;
    }

    public WaitingQueueInfo.Enter enter(String userId) {
        WaitingQueueEntryVO entry = WaitingQueueEntryVO.create(userId);
        waitingQueueRepository.add(entry);
        return new WaitingQueueInfo.Enter(entry.userId(), entry.timestamp());
    }

    public WaitingQueueInfo.Position getPosition(String userId) {
        Optional<EntryTokenVO> token = entryTokenRepository.find(userId);
        if (token.isPresent()) {
            return new WaitingQueueInfo.Position(0, null, token.get().token());
        }
        Long rank = waitingQueueRepository.findRank(userId).orElse(null);
        long position = rankCalculator.calculatePosition(rank);
        long estimatedWaitSeconds = estimatedWaitPolicy.calculate(position);
        return new WaitingQueueInfo.Position(position, estimatedWaitSeconds, null);
    }

    public void publishEntryTokens() {
        for (String userId : waitingQueueRepository.popMin(PUBLISH_BATCH_SIZE)) {
            entryTokenRepository.save(EntryTokenVO.create(userId));
        }
    }
}
