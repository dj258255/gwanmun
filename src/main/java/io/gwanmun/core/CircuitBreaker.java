package io.gwanmun.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * 손으로 짠 서킷브레이커 (Phase 6). 죽어 있는 계정계에 계속 매달리는 것을 끊는다.
 *
 * <p><b>왜 필요한가.</b> 계정계가 죽으면 모든 호출이 connect/read 타임아웃까지 기다리다 실패한다.
 * 호출마다 몇 초씩 스레드를 붙잡으니, 계정계 장애가 게이트웨이의 스레드 고갈로 <b>전파</b>된다.
 * 서킷은 연속 실패가 임계에 닿으면 통로를 끊고(OPEN), 이후 호출을 <b>즉시 실패</b>시켜 게이트웨이를
 * 지킨다. 죽은 백엔드에 1ms도 안 쓰는 것이 요점이다.
 *
 * <p><b>상태 전이.</b>
 * <pre>
 *  CLOSED --(연속 실패 ≥ failureThreshold)--> OPEN --(openWaitMs 경과)--> HALF_OPEN
 *  HALF_OPEN --(탐침 성공)--> CLOSED
 *  HALF_OPEN --(탐침 실패)--> OPEN (대기 재시작)
 * </pre>
 *
 * <ul>
 *   <li><b>CLOSED</b> — 정상. 모든 호출 통과. 연속 실패를 센다(성공하면 0으로 리셋).</li>
 *   <li><b>OPEN</b> — 차단. {@link #acquire()}가 {@link CircuitOpenException}으로 즉시 거절
 *       (계정계 호출 없음). {@code openWaitMs}가 지나면 다음 호출부터 HALF_OPEN.</li>
 *   <li><b>HALF_OPEN</b> — 탐침. 제한된 수({@code halfOpenMaxProbes})의 호출만 통과시켜 회복
 *       여부를 살핀다. 탐침이 성공하면 CLOSED로 복귀, 실패하면 다시 OPEN. 탐침이 나가 있는 동안의
 *       추가 호출은 즉시 거절 — 회복 확인 전에 트래픽을 쏟아부으면 살아나던 백엔드를 다시 눕힌다.</li>
 * </ul>
 *
 * <p>동시성은 {@code synchronized} 한 겹으로 지킨다 — 상태 전이·카운터 갱신은 나노초 단위 작업이라
 * 락 경합이 문제되지 않고, 미묘한 lock-free 코드보다 검증이 쉽다. 시계는 주입 가능해 테스트가
 * 시간을 조작할 수 있다.
 *
 * <p><b>세대(generation)로 stale 결과를 거른다 (Phase 8 — A3).</b> {@link #acquire()}가 반환하는
 * {@link Permit}은 "이 호출이 어느 상태 세대에서 허가를 받았는가"를 담는다. 상태가 한 번 전이할
 * 때마다 세대가 오른다. 결과 보고({@link #onSuccess}/{@link #onFailure}/{@link #onAborted})는 permit의
 * 세대가 현재 세대와 같을 때만 상태 전이·탐침 정원에 반영한다. 다르면 그 결과는 <b>이미 지나간
 * 상태의 것</b>(예: CLOSED에서 나갔다가 OPEN→HALF_OPEN 전이 뒤에 늦게 돌아온 호출)이라, 새 상태를
 * 오염시키지 않고 무시한다. 이렇게 하지 않으면 CLOSED에서 나간 늦은 성공이 HALF_OPEN을 거짓으로
 * 닫거나(회복 안 됐는데), 늦은 실패가 탐침 정원을 음수로 깎아 서킷이 영영 안 닫히는 오작동이 난다.
 */
public final class CircuitBreaker {

	private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

	/** 서킷의 3상태. 게이지 노출 시 ordinal을 쓴다(0=CLOSED, 1=OPEN, 2=HALF_OPEN). */
	public enum State { CLOSED, OPEN, HALF_OPEN }

	/**
	 * 통과 허가 토큰. {@link #acquire()}가 발급하고 결과 보고 시 되돌려 주는 <b>불투명 토큰</b>이다 —
	 * 호출자는 내용을 해석하지 않는다. 서킷은 이 토큰의 세대로 "이 결과가 지금도 유효한 상태의
	 * 것인지"를 판정한다. 외부에서 위조해도 세대가 안 맞으면 무시되므로 안전에 영향이 없다.
	 */
	public static final class Permit {
		private final long generation;
		private final boolean probe;

		private Permit(long generation, boolean probe) {
			this.generation = generation;
			this.probe = probe;
		}
	}

	private final String name;
	private final int failureThreshold;
	private final long openWaitMs;
	private final int halfOpenMaxProbes;
	private final LongSupplier nanoTime;

	private State state = State.CLOSED;
	private int consecutiveFailures;
	private long openedAtNanos;
	private int probesInFlight;
	// 상태가 전이할 때마다 오르는 세대 번호. permit이 이 값을 스냅샷해 stale 판정의 기준이 된다.
	private long generation;

	// 누적 관측치(메트릭·화면용).
	private long openedTotal;      // OPEN으로 전이한 횟수
	private long rejectedTotal;    // 즉시 거절한 호출 수
	private long staleResultsTotal; // 세대 불일치로 무시한 결과 보고 수(A3 — stale 귀속 방어가 실제로 작동한 횟수)

	public CircuitBreaker(String name, int failureThreshold, long openWaitMs, int halfOpenMaxProbes) {
		this(name, failureThreshold, openWaitMs, halfOpenMaxProbes, System::nanoTime);
	}

	/** 테스트용: 시계를 주입해 OPEN 대기 경과를 시간 조작으로 검증한다. */
	CircuitBreaker(String name, int failureThreshold, long openWaitMs, int halfOpenMaxProbes,
			LongSupplier nanoTime) {
		if (failureThreshold <= 0) {
			throw new IllegalArgumentException("failureThreshold 는 1 이상이어야 합니다: " + failureThreshold);
		}
		if (halfOpenMaxProbes <= 0) {
			throw new IllegalArgumentException("halfOpenMaxProbes 는 1 이상이어야 합니다: " + halfOpenMaxProbes);
		}
		this.name = name;
		this.failureThreshold = failureThreshold;
		this.openWaitMs = openWaitMs;
		this.halfOpenMaxProbes = halfOpenMaxProbes;
		this.nanoTime = nanoTime;
	}

	/**
	 * 계정계 호출 직전에 통과 허가를 받는다. 허가가 나면 호출 후 반드시 {@link #onSuccess(Permit)}·
	 * {@link #onFailure(Permit)}·{@link #onAborted(Permit)} 중 하나로 <b>받은 permit을 되돌려 주며</b>
	 * 결과를 알려야 한다(누수 방지는 호출자의 {@code finally} 몫).
	 *
	 * @return 이 호출의 통과 허가 토큰(결과 보고 시 그대로 넘긴다)
	 * @throws CircuitOpenException OPEN 중이거나, HALF_OPEN 탐침 정원이 찼을 때(호출 없이 즉시 거절)
	 */
	public synchronized Permit acquire() throws CircuitOpenException {
		if (state == State.OPEN) {
			long waitedMs = (nanoTime.getAsLong() - openedAtNanos) / 1_000_000;
			if (waitedMs < openWaitMs) {
				rejectedTotal++;
				throw new CircuitOpenException(name, String.format(
						"서킷 '%s' OPEN — 계정계 호출을 차단하고 즉시 실패합니다(탐침까지 %dms 남음)",
						name, openWaitMs - waitedMs));
			}
			transition(State.HALF_OPEN, "대기 " + openWaitMs + "ms 경과 — 탐침 허용");
			probesInFlight = 0;
		}
		if (state == State.HALF_OPEN) {
			if (probesInFlight >= halfOpenMaxProbes) {
				rejectedTotal++;
				throw new CircuitOpenException(name, String.format(
						"서킷 '%s' HALF_OPEN — 탐침 %d건이 이미 나가 있어 이 호출은 즉시 거절합니다",
						name, probesInFlight));
			}
			probesInFlight++;
			return new Permit(generation, true);
		}
		// CLOSED: 무조건 통과.
		return new Permit(generation, false);
	}

	/**
	 * 허가받은 호출이 성공했다. HALF_OPEN 탐침의 성공은 회복 신호 — CLOSED로 복귀한다.
	 *
	 * <p>permit의 세대가 지금과 다르면(이미 전이한 뒤 늦게 돌아온 stale 결과) 상태에 손대지 않는다 —
	 * CLOSED에서 나간 늦은 성공이 HALF_OPEN을 거짓으로 닫는 것을 막는다.
	 */
	public synchronized void onSuccess(Permit permit) {
		if (isStale(permit)) {
			return;
		}
		if (state == State.HALF_OPEN) {
			probesInFlight--;
			transition(State.CLOSED, "탐침 성공 — 계정계 회복 확인");
		}
		consecutiveFailures = 0;
	}

	/**
	 * 허가는 받았지만 <b>백엔드까지 가 보지도 못하고</b> 접었다 (Phase 7 — 내부 풀 고갈 등, Phase 8의
	 * {@code finally} 정산 포함). 성공도 실패도 아니므로 연속 실패 카운터를 건드리지 않는다. 단 세대가
	 * 여전히 같고 이 permit이 탐침이었다면 정원을 돌려놓는다 — 안 돌려놓으면 나가지도 않은 탐침이 정원을
	 * 영원히 차지해 서킷이 안 닫힌다. 세대가 다르면 그 탐침 정원은 전이 시 이미 리셋됐으므로 아무것도 안 한다.
	 */
	public synchronized void onAborted(Permit permit) {
		if (isStale(permit)) {
			return;
		}
		if (state == State.HALF_OPEN && permit.probe && probesInFlight > 0) {
			probesInFlight--;
		}
	}

	/**
	 * 허가받은 호출이 실패했다. CLOSED에서 임계에 닿거나 HALF_OPEN 탐침이 실패하면 OPEN.
	 *
	 * <p>permit의 세대가 지금과 다르면(이미 전이한 뒤 늦게 돌아온 stale 실패) 무시한다 — CLOSED에서
	 * 나간 늦은 실패가 새 HALF_OPEN의 탐침 정원을 음수로 깎거나 갓 닫힌 서킷을 다시 여는 것을 막는다.
	 */
	public synchronized void onFailure(Permit permit) {
		if (isStale(permit)) {
			return;
		}
		if (state == State.HALF_OPEN) {
			probesInFlight--;
			open("탐침 실패 — 계정계 아직 회복 안 됨");
			return;
		}
		if (state == State.CLOSED) {
			consecutiveFailures++;
			if (consecutiveFailures >= failureThreshold) {
				open("연속 실패 " + consecutiveFailures + "회(임계 " + failureThreshold + ")");
			}
		}
	}

	/** permit이 발급된 뒤 상태가 전이했는가(세대 불일치). stale이면 결과 보고를 무시한다. */
	private boolean isStale(Permit permit) {
		if (permit.generation != generation) {
			staleResultsTotal++;
			return true;
		}
		return false;
	}

	private void open(String why) {
		openedAtNanos = nanoTime.getAsLong();
		openedTotal++;
		transition(State.OPEN, why);
	}

	private void transition(State to, String why) {
		State from = state;
		state = to;
		generation++; // 세대를 올려, 이전 상태에서 나간 호출의 늦은 결과를 stale로 만든다.
		// 상태 전이는 운영자가 봐야 하는 사건 — 로그로 남긴다(메트릭 게이지와 한 쌍).
		log.warn("서킷 '{}' {} → {} ({})", name, from, to, why);
	}

	/** 현재 상태 스냅샷(메트릭·화면용). */
	public synchronized Stats stats() {
		long openRemainingMs = 0;
		if (state == State.OPEN) {
			openRemainingMs = Math.max(0, openWaitMs - (nanoTime.getAsLong() - openedAtNanos) / 1_000_000);
		}
		return new Stats(name, state, consecutiveFailures, failureThreshold,
				openedTotal, rejectedTotal, staleResultsTotal, openRemainingMs);
	}

	/** 서킷 상태 스냅샷. */
	public record Stats(
			String name,
			State state,
			int consecutiveFailures,
			int failureThreshold,
			long openedTotal,
			long rejectedTotal,
			long staleResultsTotal,
			long openRemainingMs
	) {
	}
}
