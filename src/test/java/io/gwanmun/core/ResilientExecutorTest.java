package io.gwanmun.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 장애 내성 실행기의 세 계약을 검증한다 —
 * (1) 조회성만 재시도한다(변경성 재시도 금지 = 이중 거래 방어),
 * (2) 거래 데드라인이 재시도와 마지막 시도의 read 타임아웃을 자른다,
 * (3) 서킷과 맞물린다(열리면 즉시 거절, 재시도 도중 열리면 원래 실패를 던진다).
 */
class ResilientExecutorTest {

	/** 가짜 시계·가짜 sleep — 실제 대기 없이 시간 흐름만 시뮬레이션한다. */
	private final AtomicLong nowNanos = new AtomicLong();

	private void advanceMs(long ms) {
		nowNanos.addAndGet(ms * 1_000_000);
	}

	private CircuitBreaker breaker(int threshold) {
		return new CircuitBreaker("test", threshold, 10_000, 1, nowNanos::get);
	}

	private ResilientExecutor executor(CircuitBreaker cb, int readTimeoutMs, long deadlineMs, int maxRetries) {
		// sleeper가 가짜 시계를 민다 — 백오프가 데드라인 계산에 포함되는 것까지 검증된다.
		return new ResilientExecutor("test", cb, readTimeoutMs, deadlineMs, maxRetries, 100,
				nowNanos::get, this::advanceMs);
	}

	@Test
	@DisplayName("조회성(INQUIRY): 실패 → 백오프 → 재시도, 성공하면 결과를 돌려준다")
	void inquiryRetriesThenSucceeds() throws Exception {
		ResilientExecutor ex = executor(breaker(100), 1000, 10_000, 2);
		AtomicInteger calls = new AtomicInteger();

		String result = ex.execute(TransactionKind.INQUIRY, timeout -> {
			if (calls.incrementAndGet() < 3) {
				advanceMs(50);
				throw new SocketTimeoutException("느린 계정계");
			}
			return "성공";
		});

		assertThat(result).isEqualTo("성공");
		assertThat(calls.get()).isEqualTo(3); // 1회 + 재시도 2회
		assertThat(ex.retriesTotal()).isEqualTo(2);
	}

	@Test
	@DisplayName("변경성(MUTATION): 첫 실패에서 그대로 던진다 — 호출은 정확히 1회(이중 거래 방어)")
	void mutationNeverRetries() {
		ResilientExecutor ex = executor(breaker(100), 1000, 10_000, 2);
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> ex.execute(TransactionKind.MUTATION, timeout -> {
			calls.incrementAndGet();
			throw new SocketTimeoutException("응답 유실");
		})).isInstanceOf(SocketTimeoutException.class);

		assertThat(calls.get()).isEqualTo(1); // 재시도 설정(2회)이 있어도 변경성은 1회로 끝
		assertThat(ex.retriesTotal()).isZero();
	}

	@Test
	@DisplayName("거래 데드라인: 남은 시간이 백오프조차 감당 못 하면 재시도를 접고 마지막 실패를 던진다")
	void deadlineStopsRetries() {
		// 데드라인 500ms, 시도마다 450ms 소모 → 첫 실패 후 백오프(100ms)를 더하면 데드라인 초과.
		ResilientExecutor ex = executor(breaker(100), 1000, 500, 2);
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> ex.execute(TransactionKind.INQUIRY, timeout -> {
			calls.incrementAndGet();
			advanceMs(450);
			throw new SocketTimeoutException("타임아웃");
		})).isInstanceOf(SocketTimeoutException.class);

		assertThat(calls.get()).isEqualTo(1); // 재시도 2회 설정이지만 데드라인이 잘랐다
	}

	@Test
	@DisplayName("마지막 시도의 read 타임아웃은 데드라인의 남은 시간으로 깎인다")
	void attemptTimeoutIsCappedByRemainingDeadline() throws Exception {
		// read 타임아웃 1000ms, 데드라인 1200ms. 1차 시도가 900ms 쓰고 실패하면
		// 백오프 100ms 후 남은 시간은 200ms — 2차 시도는 1000ms가 아니라 200ms만 허용돼야 한다.
		ResilientExecutor ex = executor(breaker(100), 1000, 1200, 2);
		List<Integer> grantedTimeouts = new ArrayList<>();

		String result = ex.execute(TransactionKind.INQUIRY, timeout -> {
			grantedTimeouts.add(timeout);
			if (grantedTimeouts.size() == 1) {
				advanceMs(900);
				throw new SocketTimeoutException("느림");
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(grantedTimeouts.get(0)).isEqualTo(1000); // 여유 있을 땐 설정값 그대로
		assertThat(grantedTimeouts.get(1)).isEqualTo(200);  // 남은 시간으로 깎였다
	}

	@Test
	@DisplayName("서킷이 이미 열려 있으면 첫 시도 전에 CircuitOpenException — 계정계 호출 0회")
	void circuitOpenRejectsBeforeFirstAttempt() throws Exception {
		CircuitBreaker cb = breaker(1);
		cb.acquire();
		cb.onFailure(); // 임계 1 → OPEN
		ResilientExecutor ex = executor(cb, 1000, 10_000, 2);
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> ex.execute(TransactionKind.INQUIRY, timeout -> {
			calls.incrementAndGet();
			return "안 와야 함";
		})).isInstanceOf(CircuitOpenException.class);

		assertThat(calls.get()).isZero();
	}

	@Test
	@DisplayName("재시도 도중 서킷이 열리면(내 실패가 임계를 채움) 남은 재시도를 접고 원래 실패를 던진다")
	void circuitOpeningDuringRetryThrowsOriginalFailure() {
		CircuitBreaker cb = breaker(1); // 첫 실패로 바로 열린다
		ResilientExecutor ex = executor(cb, 1000, 10_000, 2);
		AtomicInteger calls = new AtomicInteger();

		assertThatThrownBy(() -> ex.execute(TransactionKind.INQUIRY, timeout -> {
			calls.incrementAndGet();
			throw new SocketTimeoutException("진짜 원인");
		}))
				// 호출자에게 중요한 건 "서킷이 열렸다"가 아니라 계정계가 어떻게 실패했는가다.
				.isInstanceOf(SocketTimeoutException.class)
				.hasMessage("진짜 원인");

		assertThat(calls.get()).isEqualTo(1);
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	@DisplayName("풀 고갈(Phase 7): 서킷 실패로 계수하지 않고 그대로 던진다 — 내부 사정은 백엔드 장애가 아니다")
	void poolExhaustionDoesNotCountAsCircuitFailure() {
		CircuitBreaker cb = breaker(3);
		ResilientExecutor ex = executor(cb, 1000, 10_000, 2);
		AtomicInteger calls = new AtomicInteger();

		// 임계(3)를 넘는 4번의 풀 고갈 — 수정 전에는 RuntimeException 경로로 onFailure()가 쌓여
		// 계정계가 멀쩡해도 서킷이 열렸다(오보). 수정 후에는 한 번도 계수되지 않아야 한다.
		for (int i = 0; i < 4; i++) {
			assertThatThrownBy(() -> ex.execute(TransactionKind.INQUIRY, timeout -> {
				calls.incrementAndGet();
				throw new PoolExhaustedException("core-banking", 4, 2000);
			})).isInstanceOf(PoolExhaustedException.class);
		}

		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.CLOSED); // 서킷은 그대로
		assertThat(cb.stats().consecutiveFailures()).isZero();                 // 계수 0
		assertThat(calls.get()).isEqualTo(4);   // 재시도도 안 한다(과부하 증폭 방지) — 호출 4회뿐
		assertThat(ex.retriesTotal()).isZero();
	}

	@Test
	@DisplayName("풀 고갈이 HALF_OPEN 탐침 정원을 누수시키지 않는다 — 다음 진짜 탐침이 나갈 수 있다")
	void poolExhaustionReleasesHalfOpenProbeSlot() throws Exception {
		CircuitBreaker cb = breaker(1);
		ResilientExecutor ex = executor(cb, 1000, 10_000, 0);

		// 서킷을 열고 대기 시간을 흘려 HALF_OPEN 진입 조건을 만든다.
		assertThatThrownBy(() -> ex.execute(TransactionKind.INQUIRY, timeout -> {
			throw new SocketTimeoutException("계정계 다운");
		})).isInstanceOf(SocketTimeoutException.class);
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.OPEN);
		advanceMs(10_001);

		// 첫 탐침이 풀 고갈로 계정계까지 못 갔다 — 정원(1)을 돌려놓지 않으면 서킷이 영영 안 닫힌다.
		assertThatThrownBy(() -> ex.execute(TransactionKind.INQUIRY, timeout -> {
			throw new PoolExhaustedException("core-banking", 4, 2000);
		})).isInstanceOf(PoolExhaustedException.class);

		// 다음 호출이 탐침으로 나가 성공하면 서킷이 닫혀야 한다.
		String result = ex.execute(TransactionKind.INQUIRY, timeout -> "회복");
		assertThat(result).isEqualTo("회복");
		assertThat(cb.stats().state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}
}
