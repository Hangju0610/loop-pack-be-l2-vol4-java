package com.loopers.domain.waitingqueue;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class WaitingQueueRankCalculator {

    public long calculatePosition(Long rank) {
        if (rank == null) {
            throw new CoreException(ErrorType.NOT_FOUND, "대기열에 등록되지 않았습니다");
        }
        return rank + 1;
    }
}
