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
 * <p><b>유일성 보장 두 겹.</b>
 * <ul>
 *   <li><b>스레드 안전</b>: 일련번호는 {@link AtomicLong#incrementAndGet()} — 여러 요청 스레드가
 *       동시에 채번해도 절대 같은 번호가 안 나온다(락 없이 원자적).</li>
 *   <li><b>재기동 안전</b>: 일련번호 시작값을 "자정 이후 흐른 밀리초 × 10"으로 시드한다. 프로세스를
 *       재기동하면 시드가 그 사이 흐른 시간만큼 앞서 있으므로, 죽기 전 발급분과 겹치지 않는다
 *       (지속 발급률이 밀리초당 10건 = 초당 1만 건을 넘지 않는 한). 날짜가 바뀌면 ID의 날짜부가
 *       달라져 일련번호가 겹쳐도 ID는 다르다.</li>
 * </ul>
 *
 * <p><b>정직한 경계</b>: 단일 노드 전제다. 다중 인스턴스라면 기관코드 뒤에 노드 식별자를 넣거나
 * 중앙 채번(DB 시퀀스 등)이 필요하다 — 확장 지점.
 */
@Component
public final class TransactionIdGenerator {

	private static final String INSTITUTION_CODE = "GWMN"; // 생성주체(이 게이트웨이) 코드
	private static final String GENERATED_BY = "U";        // 생성 구분(오픈뱅킹 'U' 관례 참조)
	private static final long SEQUENCE_SPACE = 1_000_000_000L; // 일련번호 9자리
	private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private final Clock clock;
	private final AtomicLong sequence;

	public TransactionIdGenerator() {
		this(Clock.systemDefaultZone());
	}

	/** 테스트용: 시계를 주입해 "재기동 후 시드가 앞서 있음"을 시간 조작으로 검증한다. */
	TransactionIdGenerator(Clock clock) {
		this.clock = clock;
		long millisOfDay = LocalTime.now(clock).toNanoOfDay() / 1_000_000L;
		this.sequence = new AtomicLong((millisOfDay * 10) % SEQUENCE_SPACE);
	}

	/** 거래고유번호 한 개를 채번한다. 예: {@code GWMNU20260709000123456} */
	public String next() {
		long seq = sequence.incrementAndGet() % SEQUENCE_SPACE;
		return INSTITUTION_CODE + GENERATED_BY + LocalDate.now(clock).format(DATE)
				+ String.format("%09d", seq);
	}
}
