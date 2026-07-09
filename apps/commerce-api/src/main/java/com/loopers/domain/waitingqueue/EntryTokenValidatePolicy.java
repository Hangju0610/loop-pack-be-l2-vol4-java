package com.loopers.domain.waitingqueue;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class EntryTokenValidatePolicy {

    public void validate(String headerToken, String storedToken) {
        if (headerToken == null || storedToken == null) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "Entry-Token이 없습니다");
        }
        if (!headerToken.equals(storedToken)) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "Entry-Token이 일치하지 않습니다");
        }
    }
}
