package io.gwanmun.core;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 장애 내성 설정 묶음 (Phase 6). 클라이언트(잔액조회·거래내역)가 각자의 서킷·재시도·데드라인을
 * 이 값으로 만든다.
 */
@Component
public final class ResilienceSettings {

	private final long transactionDeadlineMs;
	private final int retryMax;
	private final long retryBackoffMs;
	private final int circuitFailureThreshold;
	private final long circuitOpenWaitMs;
	private final int circuitHalfOpenMaxProbes;

	@Autowired
	public ResilienceSettings(
			@Value("${gwanmun.core.resilience.transaction-deadline-ms:5000}") long transactionDeadlineMs,
			@Value("${gwanmun.core.resilience.retry-max:2}") int retryMax,
			@Value("${gwanmun.core.resilience.retry-backoff-ms:200}") long retryBackoffMs,
			@Value("${gwanmun.core.resilience.circuit.failure-threshold:3}") int circuitFailureThreshold,
			@Value("${gwanmun.core.resilience.circuit.open-wait-ms:10000}") long circuitOpenWaitMs,
			@Value("${gwanmun.core.resilience.circuit.half-open-max-probes:1}") int circuitHalfOpenMaxProbes) {
		this.transactionDeadlineMs = transactionDeadlineMs;
		this.retryMax = retryMax;
		this.retryBackoffMs = retryBackoffMs;
		this.circuitFailureThreshold = circuitFailureThreshold;
		this.circuitOpenWaitMs = circuitOpenWaitMs;
		this.circuitHalfOpenMaxProbes = circuitHalfOpenMaxProbes;
	}

	/**
	 * 장애 내성 없이(재시도 0·서킷 사실상 비활성) 도는 설정 — 스프링 밖 테스트 생성자의 기본값.
	 * Phase 5까지의 단발 호출 동작을 그대로 보존한다.
	 */
	public static ResilienceSettings none(int readTimeoutMs) {
		// 데드라인은 한 번의 호출(연결+read)이 끝나기에 충분하게만 잡는다.
		return new ResilienceSettings(readTimeoutMs + 60_000L, 0, 0, Integer.MAX_VALUE, 0, 1);
	}

	/** 거래 단위 종합 데드라인 — 재시도·백오프를 포함한 거래 전체 상한(ms). */
	public long transactionDeadlineMs() {
		return transactionDeadlineMs;
	}

	/** 조회성 거래의 최대 재시도 횟수(변경성은 항상 0 — 코드로 강제). */
	public int retryMax() {
		return retryMax;
	}

	/** 첫 재시도 백오프(ms). 이후 지수 증가(×2, ×4, ...). */
	public long retryBackoffMs() {
		return retryBackoffMs;
	}

	/** 서킷 OPEN 임계(연속 실패 수). */
	public int circuitFailureThreshold() {
		return circuitFailureThreshold;
	}

	/** OPEN 후 탐침(HALF_OPEN)까지의 대기(ms). */
	public long circuitOpenWaitMs() {
		return circuitOpenWaitMs;
	}

	/** HALF_OPEN에서 동시에 허용하는 탐침 수. */
	public int circuitHalfOpenMaxProbes() {
		return circuitHalfOpenMaxProbes;
	}

	/** 이 설정으로 서킷 하나를 만든다(백엔드별 하나). */
	public CircuitBreaker newCircuit(String name) {
		return new CircuitBreaker(name, circuitFailureThreshold, circuitOpenWaitMs, circuitHalfOpenMaxProbes);
	}

	/** 이 설정으로 실행기를 만든다(서킷과 한 쌍). */
	public ResilientExecutor newExecutor(String name, CircuitBreaker breaker, int readTimeoutMs) {
		return new ResilientExecutor(name, breaker, readTimeoutMs,
				transactionDeadlineMs, retryMax, retryBackoffMs);
	}
}
