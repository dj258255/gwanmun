package io.gwanmun.core;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * 커넥션 풀 상태를 Micrometer 지표로 노출한다 — 자체 구현한 풀({@link ConnectionPool})이
 * /actuator/prometheus 에서 스크레이프될 때마다 현재 스냅샷을 읽힌다.
 *
 * <p>두 가지 함정을 피해 등록한다.
 * <ul>
 *   <li><b>약참조 함정</b>: Micrometer 게이지는 상태 객체를 약참조로 쥔다 — 일회성 람다를 넘기면
 *       GC 후 NaN이 된다. 그래서 컨텍스트가 살아 있는 한 살아 있는 <b>클라이언트 빈</b>에 건다.</li>
 *   <li><b>{@code _created} 예약 접미사 함정</b>: Prometheus 신형 노출 형식에서 {@code _created}는
 *       예약 접미사라, "created"로 끝나는 지표명은 잘려 나간다({@code gwanmun_pool_total}로 붕괴).
 *       그래서 누적 개설 수는 {@code opened}로 명명한다.</li>
 * </ul>
 *
 * <p>현재값(active/idle)은 게이지, 누적값(opened/reused/destroyed)은 단조 증가라 함수 카운터로
 * 등록한다({@code _total} 접미사로 노출).
 *
 * <ul>
 *   <li>{@code gwanmun_pool_active{pool=...}} — 지금 대여 중인 연결 수 (게이지)</li>
 *   <li>{@code gwanmun_pool_idle{pool=...}} — 유휴로 쥐고 있는 연결 수 (게이지)</li>
 *   <li>{@code gwanmun_pool_opened_total{pool=...}} — 누적 새로 연 소켓 수(재사용이 잘 되면 안 는다)</li>
 *   <li>{@code gwanmun_pool_reused_total{pool=...}} — 누적 재사용 횟수</li>
 *   <li>{@code gwanmun_pool_destroyed_total{pool=...}} — 누적 폐기 수(늘면 연결이 자주 깨진다는 신호)</li>
 * </ul>
 */
@Component
public class ConnectionPoolMetrics {

	public ConnectionPoolMetrics(CoreBankingClient balanceClient,
			TransactionHistoryClient historyClient, MeterRegistry registry) {
		register(registry, "core-banking", balanceClient, CoreBankingClient::poolStats);
		register(registry, "txn-history", historyClient, TransactionHistoryClient::poolStats);
	}

	private static <T> void register(MeterRegistry registry, String pool, T client,
			Function<T, ConnectionPool.Stats> stats) {
		gauge(registry, pool, "gwanmun.pool.active", client, c -> stats.apply(c).active());
		gauge(registry, pool, "gwanmun.pool.idle", client, c -> stats.apply(c).idle());
		counter(registry, pool, "gwanmun.pool.opened", client, c -> stats.apply(c).created());
		counter(registry, pool, "gwanmun.pool.reused", client, c -> stats.apply(c).reused());
		counter(registry, pool, "gwanmun.pool.destroyed", client, c -> stats.apply(c).destroyed());
	}

	private static <T> void gauge(MeterRegistry registry, String pool, String name,
			T client, ToDoubleFunction<T> value) {
		Gauge.builder(name, client, value)
				.tag("pool", pool)
				.register(registry);
	}

	private static <T> void counter(MeterRegistry registry, String pool, String name,
			T client, ToDoubleFunction<T> value) {
		FunctionCounter.builder(name, client, value)
				.tag("pool", pool)
				.register(registry);
	}
}
