package io.gwanmun.ledger;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 거래고유번호 채번기. 게이트웨이를 지나는 모든 거래에 유일한 ID를 붙인다 — 장애 때 "그 거래"를
 * 앱 로그·원장·계정계 사이에서 한 줄로 꿰는 열쇠다.
 *
 * <p><b>형식(자기설명 구조)</b>: 오픈뱅킹 거래고유번호(bank_tran_id)의 "생성주체 코드 + 생성 구분 +
 * 일련번호" 구조를 참조했다.
 *
 * <pre>
 *   GWMN  U  20260709  000123456
 *   └기관┘ └구분┘ └날짜(8)┘ └일련번호(9)┘   = 22자
 * </pre>
 *
 * ID만 보고도 누가(GWMN=이 게이트웨이) 언제(날짜) 몇 번째로 채번했는지 읽힌다.
 *
 * <p><b>유일성 보장 세 겹.</b>
 * <ul>
 *   <li><b>스레드 안전</b>: 일련번호는 {@link AtomicLong#incrementAndGet()} — 여러 요청 스레드가
 *       동시에 채번해도 절대 같은 번호가 안 나온다(락 없이 원자적).</li>
 *   <li><b>재기동 안전</b>: 일련번호 시작값을 "자정 이후 흐른 밀리초 × 10"으로 시드한다. 프로세스를
 *       재기동하면 시드가 그 사이 흐른 시간만큼 앞서 있으므로, 죽기 전 발급분과 겹치지 않는다
 *       (지속 발급률이 밀리초당 10건 = 초당 1만 건을 넘지 않는 한).</li>
 *   <li><b>자정 롤오버 안전 (Phase 7)</b>: 날짜가 바뀌면 일련번호를 새 날짜의 시각 시드로 <b>재시드</b>한다.
 *       재시드 없이 이어 세면, 자정을 넘겨 살아 있던 프로세스가 새 날짜에 "어제 저녁 크기의 큰 일련번호"를
 *       발급하고 — 그 시각 시드 불변식이 깨진 상태에서 재기동·시간 경과가 겹치면 같은 날짜에 같은
 *       일련번호가 두 번 나올 수 있다(시드가 이미 지나간 구간을 나중에 다시 밟는다).</li>
 * </ul>
 *
 * <p><b>정직한 경계</b>: 단일 노드 전제다. 다중 인스턴스라면 기관코드 뒤에 노드 식별자를 넣거나
 * 중앙 채번(DB 시퀀스 등)이 필요하다 — 확장 지점. 또한 자정 전이의 아주 좁은 창(재시드와 동시에
 * 어제 날짜를 읽은 스레드)은 이론상 남는다 — 정확히 24시간 간격의 동일 시퀀스 충돌 조건이라
 * 실질 위험은 없다고 판단하고 락 없는 빠른 경로를 유지했다.
 */
@Component
public final class TransactionIdGenerator {

	private static final String INSTITUTION_CODE = "GWMN"; // 생성주체(이 게이트웨이) 코드
	private static final String GENERATED_BY = "U";        // 생성 구분(오픈뱅킹 'U' 관례 참조)
	private static final long SEQUENCE_SPACE = 1_000_000_000L; // 일련번호 9자리
	private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private final Clock clock;
	private final AtomicLong sequence;
	private volatile LocalDate seededDate; // 이 날짜 기준으로 시드됐다 — 날짜가 바뀌면 재시드(Phase 7).

	public TransactionIdGenerator() {
		this(Clock.systemDefaultZone());
	}

	/** 테스트용: 시계를 주입해 "재기동 후 시드가 앞서 있음"·자정 롤오버를 시간 조작으로 검증한다. */
	TransactionIdGenerator(Clock clock) {
		this.clock = clock;
		this.seededDate = LocalDate.now(clock);
		this.sequence = new AtomicLong(timeSeed(clock));
	}

	/** 시각 기반 시드 — 자정 이후 흐른 밀리초 × 10. */
	private static long timeSeed(Clock clock) {
		long millisOfDay = LocalTime.now(clock).toNanoOfDay() / 1_000_000L;
		return (millisOfDay * 10) % SEQUENCE_SPACE;
	}

	/** 거래고유번호 한 개를 채번한다. 예: {@code GWMNU20260709000123456} */
	public String next() {
		LocalDate today = LocalDate.now(clock);
		if (!today.equals(seededDate)) {
			reseed(today); // 자정 롤오버 — 새 날짜의 시각 시드로 일련번호를 되감는다(불변식 복원).
		}
		long seq = sequence.incrementAndGet() % SEQUENCE_SPACE;
		return INSTITUTION_CODE + GENERATED_BY + today.format(DATE) + String.format("%09d", seq);
	}

	/** 날짜 전이 시에만 락을 잡는다 — 평상시 채번은 락 없는 AtomicLong 경로 그대로다. */
	private synchronized void reseed(LocalDate today) {
		if (today.equals(seededDate)) {
			return; // 다른 스레드가 이미 재시드했다.
		}
		sequence.set(timeSeed(clock));
		seededDate = today;
	}
}
