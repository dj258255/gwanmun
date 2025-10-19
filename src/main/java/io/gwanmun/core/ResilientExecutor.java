package io.gwanmun.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 계정계 TCP 호출을 장애 내성 세 겹으로 감싼다 (Phase 6) —
 * <b>①거래 단위 종합 데드라인 ②조회성 한정 재시도 ③서킷브레이커</b>.
 *
 * <p><b>① 거래 데드라인.</b> 소켓 read 타임아웃은 "호출 한 번"의 제한일 뿐이다. 재시도가 붙는 순간
 * 거래 하나가 read 타임아웃 × 시도 횟수 + 백오프만큼 늘어질 수 있다 — 호출자는 그만큼 못 기다린다.
 * 그래서 거래 전체에 데드라인을 하나 더 둔다: 새 시도는 데드라인 안에서만 출발하고, <b>매 시도의
 * read 타임아웃도 남은 시간으로 깎는다</b>(마지막 시도가 데드라인을 넘겨 늘어지지 않게).
 *
 * <p><b>② 재시도는 조회성만.</b> {@link TransactionKind#INQUIRY}만 지수 백오프로 제한 재시도한다.
 * {@link TransactionKind#MUTATION}은 첫 실패에서 그대로 던진다 — 응답을 못 받은 변경성 거래를
 * 재전송하면 이중 거래다. 그 거래는 UNKNOWN으로 남겨 상태조회·망취소의 해소 절차로 확정 짓는다.
 *
 * <p><b>③ 서킷브레이커.</b> 매 시도가 {@link CircuitBreaker#acquire()}를 거친다. 서킷이 열려 있으면
 * 계정계 호출 없이 즉시 거절되고, 각 시도의 성공/실패가 서킷 카운터에 들어간다. 재시도 도중 서킷이
 * 열리면(내 실패가 임계를 채운 경우 포함) 남은 재시도를 접고 <b>원래의 실패를</b> 던진다 —
 * 호출자에겐 "서킷이 열렸다"보다 "계정계가 이렇게 실패했다"가 진짜 원인이다.
 *
 * <p><b>서킷은 백엔드 실패만 센다 (Phase 7).</b> {@link PoolExhaustedException}(내부 커넥션 풀 고갈)은
 * 계정계가 멀쩡한데 게이트웨이 쪽 자원이 모자란 것이라 서킷 실패로 계수하지 않고 그대로 올린다 —
 * 내부 사정으로 서킷을 열면 멀쩡한 백엔드로 가는 통로까지 끊는 오보가 된다. 재시도도 하지 않는다:
 * 풀은 이미 borrow-timeout 만큼 기다렸고, 과부하 상황의 재시도는 부하를 증폭할 뿐이다.
 */
public final class ResilientExecutor {

	private static final Logger log = LoggerFactory.getLogger(ResilientExecutor.class);

	/** 계정계 호출 한 번. 인자로 받은 read 타임아웃(ms)을 이번 시도에 적용해야 한다. */
	@FunctionalInterface
	public interface Attempt<T> {
		T call(int readTimeoutMs) throws IOException;
	}

	/** 재시도 백오프 대기(테스트에서 실제 sleep 없이 검증할 수 있게 주입). */
	@FunctionalInterface
	interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	private final String name;
	private final CircuitBreaker breaker;
	private final int readTimeoutMs;
	private final long deadlineMs;
	private final int maxRetries;
	private final long backoffMs;
	private final LongSupplier nanoTime;
	private final Sleeper sleeper;
	private final AtomicLong retriesTotal = new AtomicLong();

	public ResilientExecutor(String name, CircuitBreaker breaker, int readTimeoutMs,
			long deadlineMs, int maxRetries, long backoffMs) {
		this(name, breaker, readTimeoutMs, deadlineMs, maxRetries, backoffMs,
				System::nanoTime, Thread::sleep);
	}

	/** 테스트용: 시계·sleep을 주입한다. */
	ResilientExecutor(String name, CircuitBreaker breaker, int readTimeoutMs,
			long deadlineMs, int maxRetries, long backoffMs, LongSupplier nanoTime, Sleeper sleeper) {
		this.name = name;
		this.breaker = breaker;
		this.readTimeoutMs = readTimeoutMs;
		this.deadlineMs = deadlineMs;
		this.maxRetries = maxRetries;
		this.backoffMs = backoffMs;
		this.nanoTime = nanoTime;
		this.sleeper = sleeper;
	}

	/**
	 * 호출 하나를 데드라인·재시도·서킷 안에서 실행한다.
	 *
	 * @throws CircuitOpenException 첫 시도 전부터 서킷이 열려 있을 때(계정계 호출 없음)
	 * @throws IOException          모든 시도가 실패했을 때(마지막 실패를 그대로 — 3값 판정이 원인을 보게)
	 */
	public <T> T execute(TransactionKind kind, Attempt<T> attempt) throws IOException {
		long startNanos = nanoTime.getAsLong();
		int maxAttempts = kind.retryable() ? 1 + maxRetries : 1;
		IOException last = null;

		for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
			if (attemptNo > 1) {
				// 지수 백오프: backoffMs, backoffMs*2, backoffMs*4, ...
				long backoff = backoffMs << (attemptNo - 2);
				if (elapsedMs(startNanos) + backoff >= deadlineMs) {
					log.warn("[{}] 거래 데드라인({}ms) 소진 — 재시도를 접습니다(시도 {}회로 종료)",
							name, deadlineMs, attemptNo - 1);
					break;
				}
				try {
					sleeper.sleep(backoff);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("재시도 백오프 대기 중 인터럽트", last != null ? last : e);
				}
				retriesTotal.incrementAndGet();
				log.warn("[{}] 재시도 {}/{} (백오프 {}ms 후): 직전 실패 = {}",
						name, attemptNo - 1, maxRetries, backoff, last == null ? "?" : last.toString());
			}

			long remainingMs = deadlineMs - elapsedMs(startNanos);
			if (remainingMs <= 0) {
				break;
			}

			CircuitBreaker.Permit permit;
			try {
				permit = breaker.acquire();
			} catch (CircuitOpenException e) {
				if (last != null) {
					// 재시도 도중 서킷이 열림 — 원인이었던 실패를 그대로 던진다.
					throw last;
				}
				throw e;
			}

			// 받은 허가는 반드시 정산한다. 성공/실패/중단 어느 분기로도 못 갚고 빠져나가면(Error 등)
			// finally가 미사용으로 반납한다 — HALF_OPEN 탐침 정원 누수 방지(Phase 8).
			boolean settled = false;
			try {
				// 이번 시도의 read 타임아웃 = min(설정값, 데드라인까지 남은 시간).
				int attemptTimeoutMs = (int) Math.min(readTimeoutMs, remainingMs);
				T result = attempt.call(attemptTimeoutMs);
				breaker.onSuccess(permit);
				settled = true;
				return result;
			} catch (IOException e) {
				breaker.onFailure(permit);
				settled = true;
				last = e;
				if (!kind.retryable()) {
					// 변경성 거래: 재시도 금지. 실패를 그대로 올려 UNKNOWN/FAILED 판정에 맡긴다.
					throw e;
				}
			} catch (PoolExhaustedException e) {
				// 내부 풀 고갈 — 백엔드 실패가 아니므로 서킷에 계수하지 않고, 재시도 없이 그대로 올린다.
				// (풀이 이미 borrow-timeout 만큼 기다렸다. 과부하에서의 재시도는 부하 증폭이다.)
				// 받아 둔 허가는 성공/실패 아닌 "미사용"으로 반납한다(HALF_OPEN 탐침 정원 누수 방지).
				breaker.onAborted(permit);
				settled = true;
				throw e;
			} catch (RuntimeException e) {
				breaker.onFailure(permit);
				settled = true;
				throw e;
			} finally {
				if (!settled) {
					// Error·미분류 Throwable로 위 분기를 못 탄 경우 — 허가를 미사용으로 반납한다.
					breaker.onAborted(permit);
				}
			}
		}

		throw last != null ? last
				: new IOException("[" + name + "] 데드라인(" + deadlineMs + "ms) 안에 시도조차 못 했습니다.");
	}

	/** 누적 재시도 횟수(메트릭용). */
	public long retriesTotal() {
		return retriesTotal.get();
	}

	private long elapsedMs(long startNanos) {
		return (nanoTime.getAsLong() - startNanos) / 1_000_000;
	}
}
