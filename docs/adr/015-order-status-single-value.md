# ADR-015: OrderStatus 다중 상태 도입

- 날짜: 2026-05-28 (수정 — 단일 COMPLETED → 다중 상태로 변경)
- 상태: 승인됨

## 결정

`OrderStatus`는 `PENDING`, `PAID`, `COMPLETED`, `CANCELLED` 4개의 상태를 가진다.

```java
public enum OrderStatus {
    PENDING,
    PAID,
    COMPLETED,
    CANCELLED
}
```

### 상태 흐름

```
PENDING → PAID → COMPLETED
        ↘
         CANCELLED
```

| 상태 | 의미 |
|---|---|
| `PENDING` | 주문 생성 직후 — 결제 대기 중 |
| `PAID` | 결제 완료 — 처리 중 |
| `COMPLETED` | 주문 최종 완료 |
| `CANCELLED` | 주문 취소 |

## 근거

결제 흐름 없이 `COMPLETED` 단일 값으로 운영하면 향후 결제·취소 기능 추가 시 기존 주문 데이터와 상태 의미가 충돌한다. 미리 상태를 정의해두면 기능 확장 시 enum 값 추가 없이 전이 로직만 구현하면 된다.

## 현재 구현 범위

결제 게이트웨이 연동이 없으므로 주문 생성 시 `PENDING`으로 시작하고, 현재는 즉시 `COMPLETED`로 전이한다. 향후 결제 연동 추가 시 `PENDING → PAID → COMPLETED` 흐름으로 확장한다.
