package com.loopers.interfaces.api.waitingqueue;

import com.loopers.application.waitingqueue.WaitingQueueApplicationService;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/queue")
public class WaitingQueueV1Controller {

    private final WaitingQueueApplicationService waitingQueueApplicationService;

    @PostMapping("/enter")
    public ApiResponse<WaitingQueueV1Dto.EnterResponse> enter(@RequestParam String userId) {
        return ApiResponse.success(WaitingQueueV1Dto.EnterResponse.from(
                waitingQueueApplicationService.enter(userId)
        ));
    }

    @GetMapping("/position")
    public ApiResponse<WaitingQueueV1Dto.PositionResponse> getPosition(@RequestParam String userId) {
        return ApiResponse.success(WaitingQueueV1Dto.PositionResponse.from(
                waitingQueueApplicationService.getPosition(userId)
        ));
    }
}
