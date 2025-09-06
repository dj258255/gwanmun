package io.gwanmun.gateway.filter;

import io.gwanmun.gateway.GatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 유량제어 필터 — 체인의 셋째 마디. 클라이언트별 토큰버킷으로 분당 요청량을 제한한다.
 * 토큰이 있으면 통과, 없으면 <b>429 + Retry-After</b>로 끊는다.
 *
 * <p><b>스레드 안전.</b> 클라이언트마다 버킷 하나를 {@link ConcurrentHashMap}에 두고,
 * {@code computeIfAbsent}로 원자적으로 만든다(동시에 첫 요청이 여럿 와도 버킷은 하나만 생김).
 * 소비 자체는 {@link TokenBucket#tryConsume()}가 버킷 단위로 동기화하므로, 여러 스레드가 같은
 * 클라이언트 버킷을 두드려도 토큰이 정확히 하나씩만 빠진다(초과 소비·유실 없음).
 */
@Component
public class RateLimitFilter implements GatewayFilter {

	private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
	private final double capacity;
	private final double refillPerSecond;
	private final LongSupplier clock;
	private final MeterRegistry meterRegistry;

	@Autowired
	public RateLimitFilter(GatewayProperties props, MeterRegistry meterRegistry) {
		this(props.getRateCapacity(), props.getRateRefillPerMinute() / 60.0, System::nanoTime,
				meterRegistry);
	}

	/** 테스트용: 용량·초당 보충·시계를 직접 주입한다(가짜 시계로 시간을 흘려 검증). */
	public RateLimitFilter(double capacity, double refillPerSecond, LongSupplier clock) {
		this(capacity, refillPerSecond, clock, new SimpleMeterRegistry());
	}

	public RateLimitFilter(double capacity, double refillPerSecond, LongSupplier clock,
			MeterRegistry meterRegistry) {
		this.capacity = capacity;
		this.refillPerSecond = refillPerSecond;
		this.clock = clock;
		this.meterRegistry = meterRegistry;
	}

	@Override
	public void filter(GatewayRequest request, GatewayResponse response, GatewayFilterChain chain) {
		String clientId = request.clientId(); // 인증 필터가 채워 준 값.
		TokenBucket bucket = buckets.computeIfAbsent(clientId,
				k -> new TokenBucket(capacity, refillPerSecond, clock));

		if (bucket.tryConsume()) {
			// 커스텀 메트릭: 토큰 소비(통과) 카운터 — 클라이언트별.
			meterRegistry.counter("gwanmun.ratelimit.consumed", "client", clientId).increment();
			response.header("X-RateLimit-Remaining", Long.toString(bucket.remaining()));
			chain.next(request, response);
			return;
		}

		// 커스텀 메트릭: 토큰 거절(429) 카운터 — 유량제어가 실제로 얼마나 끊고 있는지.
		meterRegistry.counter("gwanmun.ratelimit.rejected", "client", clientId).increment();
		long retryMs = bucket.millisUntilRefill();
		long retrySec = Math.max(1, (long) Math.ceil(retryMs / 1000.0));
		response.header("Retry-After", Long.toString(retrySec)); // 초 단위(HTTP 관례).
		response.header("X-RateLimit-Remaining", "0");
		response.block(429, "요청이 너무 잦습니다(클라이언트 '" + clientId
				+ "' 분당 한도 초과). " + retrySec + "초 후 재시도하세요.");
	}

	@Override
	public int order() {
		return 30;
	}
}
