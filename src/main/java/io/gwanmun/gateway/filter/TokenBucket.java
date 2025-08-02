package io.gwanmun.gateway.filter;

import java.util.function.LongSupplier;

/**
 * 토큰버킷 — 유량제어의 심장. 버킷에 토큰이 최대 {@code capacity}개까지 담기고, 시간이 흐르면
 * 정해진 속도로 다시 채워진다. 요청 한 건이 토큰 하나를 소비하고, 토큰이 없으면 거절(429)한다.
 *
 * <p><b>시계 함정.</b> 보충량은 "지난번 이후 흐른 시간 × 속도"로 계산하는데, 이때 벽시계
 * (System.currentTimeMillis)를 쓰면 NTP 보정이나 서머타임으로 시간이 <b>거꾸로</b> 갈 수 있어
 * 음수 경과·순간 폭증이 생긴다. 그래서 단조 증가가 보장된 {@link System#nanoTime()}을 쓴다
 * (주입 가능한 {@link LongSupplier}로 두어 테스트에서 시간을 마음대로 흘린다).
 *
 * <p><b>스레드 안전.</b> 한 클라이언트의 버킷을 여러 요청 스레드가 동시에 두드릴 수 있어,
 * 상태를 바꾸는 {@link #tryConsume()}·{@link #millisUntilRefill()}을 {@code synchronized}로 묶는다.
 * 버킷은 클라이언트별로 하나라 락 경합 범위가 그 클라이언트로 국한된다.
 */
public final class TokenBucket {

	private final double capacity;
	private final double refillPerNano; // 나노초당 채워지는 토큰 수
	private final LongSupplier clock;   // nanoTime 소스(테스트 주입용)

	private double tokens;
	private long lastRefillNanos;

	public TokenBucket(double capacity, double refillPerSecond, LongSupplier nanoClock) {
		if (capacity <= 0 || refillPerSecond <= 0) {
			throw new IllegalArgumentException("capacity·refill은 양수여야 합니다.");
		}
		this.capacity = capacity;
		this.refillPerNano = refillPerSecond / 1_000_000_000.0;
		this.clock = nanoClock;
		this.tokens = capacity; // 처음엔 가득 찬 상태로 시작(순간 허용량 = capacity).
		this.lastRefillNanos = nanoClock.getAsLong();
	}

	/** 토큰 하나를 소비 시도. 있으면 소비하고 true(통과), 없으면 false(429). */
	public synchronized boolean tryConsume() {
		refill();
		if (tokens >= 1.0) {
			tokens -= 1.0;
			return true;
		}
		return false;
	}

	/** 지금 남은 토큰 수(정수 내림). 통과 시 X-RateLimit-Remaining 헤더로 노출한다. */
	public synchronized long remaining() {
		refill();
		return (long) Math.floor(tokens);
	}

	/** 다음 토큰 한 개가 찰 때까지 남은 밀리초(올림). 429일 때 Retry-After 계산용. */
	public synchronized long millisUntilRefill() {
		refill();
		if (tokens >= 1.0) {
			return 0;
		}
		double needed = 1.0 - tokens;
		double nanos = needed / refillPerNano;
		return (long) Math.ceil(nanos / 1_000_000.0);
	}

	/** 흐른 시간만큼 토큰을 채운다. 단조 시계라 경과는 음수가 될 수 없다(0으로 방어). */
	private void refill() {
		long now = clock.getAsLong();
		long elapsed = now - lastRefillNanos;
		if (elapsed > 0) {
			tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
			lastRefillNanos = now;
		}
	}
}
