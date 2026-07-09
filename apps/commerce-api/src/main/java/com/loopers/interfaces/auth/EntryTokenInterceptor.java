package com.loopers.interfaces.auth;

import com.loopers.application.waitingqueue.WaitingQueueApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@RequiredArgsConstructor
public class EntryTokenInterceptor implements HandlerInterceptor {

    private static final String ENTRY_TOKEN_HEADER = "X-Loopers-Entry-Token";

    private final WaitingQueueApplicationService waitingQueueApplicationService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }

        String userId = (String) request.getAttribute(LoginUserArgumentResolver.USER_ID_ATTRIBUTE);
        String entryToken = request.getHeader(ENTRY_TOKEN_HEADER);
        waitingQueueApplicationService.validateEntryToken(userId, entryToken);
        return true;
    }
}
