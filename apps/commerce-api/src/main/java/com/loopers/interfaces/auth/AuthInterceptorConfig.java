package com.loopers.interfaces.auth;

import com.loopers.application.user.UserApplicationService;
import com.loopers.application.waitingqueue.WaitingQueueApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@RequiredArgsConstructor
@Configuration
public class AuthInterceptorConfig implements WebMvcConfigurer {

    private final UserApplicationService userApplicationService;
    private final WaitingQueueApplicationService waitingQueueApplicationService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserAuthInterceptor(userApplicationService))
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/users",
                        "/api/v1/brands/*",
                        "/api/v1/products",
                        "/api/v1/products/*",
                        "/api/v1/rankings",
                        "/api/v1/payments/callback",
                        // 대기열 경로는 무인증 — 요청당 BCrypt 가 처리량을 캡핑해 대기열의
                        // 다운스트림 보호 역할을 무력화한다 (waiting-queue 요구사항 5-1-1)
                        "/api/v1/queue/enter",
                        "/api/v1/queue/position"
                );

        registry.addInterceptor(new OptionalUserAuthInterceptor(userApplicationService))
                .addPathPatterns("/api/v1/products/*");

        registry.addInterceptor(new EntryTokenInterceptor(waitingQueueApplicationService))
                .addPathPatterns("/api/v1/orders");

        registry.addInterceptor(new AdminAuthInterceptor())
                .addPathPatterns("/api-admin/v1/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginUserArgumentResolver());
    }
}
