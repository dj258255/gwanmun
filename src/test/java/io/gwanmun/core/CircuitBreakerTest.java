package io.gwanmun.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서킷브레이커의 상태 전이를 가짜 시계로 검증한다 —
 * CLOSED→(임계)→OPEN→(대기)→HALF_OPEN→(탐침 성공/실패)→CLOSED/OPEN.
 */
class CircuitBreakerTest {

	/** 가짜 시계 — 테스트가 시간을 밀리초 단위로 민다. */
	private final AtomicLong nowNanos = new AtomicLong();

	private CircuitBreaker breaker(int threshold, long openWaitMs, int probes) {
		return new CircuitBreaker("test", threshold, openWaitMs, probes, nowNanos::get);
	}

	private void advanceMs(long ms) {
		nowNanos.addAndGet(ms * 1_000_000);
	}

	/** 허가받고 실패 한 번(호출 실패 시뮬레이션). */
	private void failOnce(CircuitBreaker cb) throws CircuitOpenException {
		cb.acquire();
		cb.onFailure();
	}

	@Test
	@DisplayName("연속 실패가 임계에 닿으면 CLOSED → OPEN, 이후 호출은 즉시 거절된다")
	void opensAtThresholdAndRejectsImmediately() throws Exception {
		CircuitBreaker cb = breaker(3, 10_000, 1);

		failOnce(cb);
		failOnce(cb);
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.CLOSED); // 2회까지는 버틴다
		failOnce(cb);
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(cb.stats().openedTotal()).isEqualTo(1);

		// OPEN 중엔 계정계 호출 없이 즉시 거절 — 거절 수가 센다.
		assertThatThrownBy(cb::acquire).isInstanceOf(CircuitOpenException.class);
		assertThatThrownBy(cb::acquire).isInstanceOf(CircuitOpenException.class);
		assertThat(cb.stats().rejectedTotal()).isEqualTo(2);
	}

	@Test
	@DisplayName("성공이 끼면 연속 실패 카운트가 리셋된다 — 산발 실패로는 안 열린다")
	void successResetsConsecutiveFailures() throws Exception {
		CircuitBreaker cb = breaker(3, 10_000, 1);

		failOnce(cb);
		failOnce(cb);
		cb.acquire();
		cb.onSuccess(); // 리셋
		failOnce(cb);
		failOnce(cb);

		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(cb.stats().consecutiveFailures()).isEqualTo(2);
	}

	@Test
	@DisplayName("OPEN에서 대기가 지나면 HALF_OPEN — 탐침 1건만 통과하고 나머지는 즉시 거절")
	void halfOpenAllowsLimitedProbes() throws Exception {
		CircuitBreaker cb = breaker(1, 5_000, 1);
		failOnce(cb); // 임계 1 → 즉시 OPEN

		advanceMs(4_999);
		assertThatThrownBy(cb::acquire).isInstanceOf(CircuitOpenException.class); // 아직 대기 중

		advanceMs(2);
		assertThatCode(cb::acquire).doesNotThrowAnyException(); // 탐침 1건 통과
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

		// 탐침이 나가 있는 동안의 두 번째 호출은 거절 — 회복 확인 전에 트래픽을 쏟지 않는다.
		assertThatThrownBy(cb::acquire).isInstanceOf(CircuitOpenException.class);
	}

	@Test
	@DisplayName("HALF_OPEN 탐침이 성공하면 CLOSED로 복귀한다")
	void probeSuccessCloses() throws Exception {
		CircuitBreaker cb = breaker(1, 5_000, 1);
		failOnce(cb);
		advanceMs(5_001);

		cb.acquire();   // 탐침
		cb.onSuccess(); // 회복 확인

		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(cb.stats().consecutiveFailures()).isZero();
		// 회복 후 정상 호출이 자유롭게 통과한다.
		assertThatCode(cb::acquire).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("HALF_OPEN 탐침이 실패하면 다시 OPEN — 대기가 처음부터 다시 시작된다")
	void probeFailureReopens() throws Exception {
		CircuitBreaker cb = breaker(1, 5_000, 1);
		failOnce(cb);
		advanceMs(5_001);

		cb.acquire();   // 탐침
		cb.onFailure(); // 아직 안 살아났다

		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(cb.stats().openedTotal()).isEqualTo(2);

		// 대기가 리셋됐으므로 바로는 또 거절.
		assertThatThrownBy(cb::acquire).isInstanceOf(CircuitOpenException.class);
		// 다시 기다리면 또 탐침 기회가 온다.
		advanceMs(5_001);
		assertThatCode(cb::acquire).doesNotThrowAnyException();
	}
}
