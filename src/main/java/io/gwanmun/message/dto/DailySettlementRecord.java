package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 당일 처리내역 조회 응답의 <b>반복 레코드</b> 한 건 (Phase 9). 계정계가 <b>자기 원장 기준으로</b>
 * 그 날 처리한 거래 하나를 서술한다 — 게이트웨이 원장과 tranId로 대조할 계정계측 진실이다.
 *
 * <pre>
 * 오프셋  길이  필드          타입      예시
 *   0    22    tranId       TEXT     "GWMNU20260709000000001"  (거래고유번호 — 대조 열쇠)
 *  22    14    accountNo    NUMERIC  "12345678901234"
 *  36    12    amount       NUMERIC  "000006879445"  (금액 — 잔액조회는 잔액)
 *  48     2    statusFlag   TEXT     "00"=정상 처리 / "99"=취소됨(망취소 반영)
 *  ---------------------------------------------------------
 *  총 50 byte
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailySettlementRecord {

	/** 정상 처리(취소 안 됨). */
	public static final String NORMAL = "00";
	/** 취소됨(망취소가 반영된 원거래). */
	public static final String CANCELED = "99";

	/** 거래고유번호 — 게이트웨이 원장과 대조하는 유일한 열쇠. */
	@Field(order = 1, length = 22, type = FieldType.TEXT)
	private String tranId;

	/** 계좌번호. */
	@Field(order = 2, length = 14, type = FieldType.NUMERIC)
	private String accountNo;

	/** 금액(원). */
	@Field(order = 3, length = 12, type = FieldType.NUMERIC)
	private String amount;

	/** 처리 상태: "00"=정상 / "99"=취소됨. */
	@Field(order = 4, length = 2, type = FieldType.TEXT)
	private String statusFlag;
}
