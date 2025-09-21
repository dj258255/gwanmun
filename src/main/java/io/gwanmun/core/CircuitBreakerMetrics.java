package io.gwanmun.core;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

/**
 * 서킷브레이커 상태를 Micrometer 지표로 노출한다 (Phase 6). {@link ConnectionPoolMetrics}와 같은
 * 함정 회피를 따른다 — 게이지는 살아 있는 클라이언트 빈에 걸고(약참조 함정), 지표명은
 * {@code _created} 예약 접미사를 피한다.
 *
 * <ul>
 *   <li>{@code gwanmun_circuit_state{circuit=...}} — 현재 상태 (0=CLOSED, 1=OPEN, 2=HALF_OPEN, 게이지)</li>
 *   <li>{@code gwanmun_circuit_consecutive_failures{circuit=...}} — 연속 실패 수 (게이지)</li>
 *   <li>{@code gwanmun_circuit_opened_total{circuit=...}} — OPEN으로 전이한 누적 횟수</li>
 *   <li>{@code gwanmun_circuit_rejected_total{circuit=...}} — OPEN/탐침 정원 초과로 즉시 거절한 누적 호출 수</li>
 *   <li>{@code gwanmun_core_retries_total{backend=...}} — 조회성 거래의 누적 재시도 횟수</li>
 * </ul>
 */
@Component
public class CircuitBreakerMetrics {

	public CircuitBreakerMetrics(CoreBankingClient balanceClient,
			TransactionHistoryClient historyClient, MeterRegistry registry) {
		register(registry, "core-banking", balanceClient,
				CoreBankingClient::circuitStats, CoreBankingClient::retriesTotal);
		register(registry, "txn-history", historyClient,
				TransactionHistoryClient::circuitStats, TransactionHistoryClient::retriesTotal);
	}

	private static <T> void register(MeterRegistry registry, String circuit, T client,
			Function<T, CircuitBreaker.Stats> stats, ToLongFunction<T> retries) {
		gauge(registry, circuit, "gwanmun.circuit.state", client,
				c -> stats.apply(c).state().ordinal());
		gauge(registry, circuit, "gwanmun.circuit.consecutive.failures", client,
				c -> stats.apply(c).consecutiveFailures());
		counter(registry, "circuit", circuit, "gwanmun.circuit.opened", client,
				c -> stats.apply(c).openedTotal());
		counter(registry, "circuit", circuit, "gwanmun.circuit.rejected", client,
				c -> stats.apply(c).rejectedTotal());
		counter(registry, "backend", circuit, "gwanmun.core.retries", client,
				c -> (double) retries.applyAsLong(c));
	}

	private static <T> void gauge(MeterRegistry registry, String circuit, String name,
			T client, ToDoubleFunction<T> value) {
		Gauge.builder(name, client, value)
				.tag("circuit", circuit)
				.register(registry);
	}

	private static <T> void counter(MeterRegistry registry, String tagKey, String tagValue,
			String name, T client, ToDoubleFunction<T> value) {
		FunctionCounter.builder(name, client, value)
				.tag(tagKey, tagValue)
				.register(registry);
	}
}
