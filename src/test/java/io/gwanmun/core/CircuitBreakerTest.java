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
		cb.onFailure(cb.acquire());
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
		cb.onSuccess(cb.acquire()); // 리셋
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

		cb.onSuccess(cb.acquire()); // 탐침 성공 — 회복 확인

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

		cb.onFailure(cb.acquire()); // 탐침 실패 — 아직 안 살아났다

		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(cb.stats().openedTotal()).isEqualTo(2);

		// 대기가 리셋됐으므로 바로는 또 거절.
		assertThatThrownBy(cb::acquire).isInstanceOf(CircuitOpenException.class);
		// 다시 기다리면 또 탐침 기회가 온다.
		advanceMs(5_001);
		assertThatCode(cb::acquire).doesNotThrowAnyException();
	}

	// --- A3: 서킷 stale 결과 귀속 (Phase 8) ---
	// "이 결과가 어느 상태 세대에서 나간 호출의 것인가"를 permit 세대로 검증한다.
	// CLOSED에서 나갔다가 OPEN→HALF_OPEN 전이 뒤에 늦게 돌아온 결과가 새 상태를 오염시키면 안 된다.

	@Test
	@DisplayName("A3: CLOSED에서 나간 늦은 성공은 HALF_OPEN을 거짓으로 닫지 못한다(stale 무시)")
	void staleSuccessFromClosedDoesNotFalselyCloseHalfOpen() throws Exception {
		CircuitBreaker cb = breaker(1, 5_000, 1);

		// (1) 호출 하나가 CLOSED에서 허가를 받고 나간다 — 아직 결과가 안 돌아왔다.
		CircuitBreaker.Permit staleClosed = cb.acquire();

		// (2) 그 사이 다른 실패가 서킷을 연다: CLOSED → OPEN.
		failOnce(cb);
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.OPEN);

		// (3) 대기가 지나 탐침이 나간다: OPEN → HALF_OPEN(정원 1을 이 탐침이 차지).
		advanceMs(5_001);
		CircuitBreaker.Permit probe = cb.acquire();
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

		// (4) 이제서야 (1)의 CLOSED 호출이 성공으로 돌아온다. 세대가 달라 무시돼야 한다 —
		//     무시하지 않으면 회복을 확인하지도 않은 채 서킷이 거짓으로 닫힌다.
		cb.onSuccess(staleClosed);
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.HALF_OPEN); // 여전히 탐침 중
		assertThat(cb.stats().staleResultsTotal()).isEqualTo(1);

		// (5) 진짜 탐침이 성공하면 그때 닫힌다.
		cb.onSuccess(probe);
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	@DisplayName("A3: CLOSED에서 나간 늦은 실패는 HALF_OPEN 탐침 정원을 깎거나 서킷을 다시 열지 못한다")
	void staleFailureFromClosedDoesNotCorruptHalfOpen() throws Exception {
		CircuitBreaker cb = breaker(1, 5_000, 1);

		CircuitBreaker.Permit staleClosed = cb.acquire(); // CLOSED에서 나간 호출
		failOnce(cb);                                     // 다른 실패가 OPEN으로 전이
		advanceMs(5_001);
		CircuitBreaker.Permit probe = cb.acquire();       // HALF_OPEN 탐침(정원 1 사용)
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

		// 낡은 CLOSED 호출이 늦게 실패로 돌아온다. 무시하지 않으면 탐침 정원을 음수로 깎고
		// 갓 진입한 HALF_OPEN을 다시 OPEN으로 돌려(대기 재시작) 회복 판단을 통째로 뒤엎는다.
		cb.onFailure(staleClosed);
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.HALF_OPEN); // 재개방 없음
		assertThat(cb.stats().staleResultsTotal()).isEqualTo(1);

		// 정원이 온전하므로, 진짜 탐침의 성공이 정상적으로 서킷을 닫는다.
		cb.onSuccess(probe);
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(cb.stats().consecutiveFailures()).isZero();
	}

	@Test
	@DisplayName("A3: 세대가 같은(정상) 결과는 그대로 반영된다 — stale 방어가 정상 경로를 막지 않는다")
	void sameGenerationResultsStillApply() throws Exception {
		CircuitBreaker cb = breaker(2, 5_000, 1);

		// 같은 CLOSED 세대에서 두 번 실패하면 정상적으로 열린다(stale 아님).
		failOnce(cb);
		failOnce(cb);
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(cb.stats().staleResultsTotal()).isZero(); // 무시된 결과 없음
	}
}
