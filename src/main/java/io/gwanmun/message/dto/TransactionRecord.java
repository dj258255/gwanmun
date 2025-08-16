package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래내역 조회 응답의 <b>반복 레코드</b> 한 건 (학습용 합성 스펙). 각 레코드는 고정 55byte이고,
 * {@link TransactionHistoryHeader} 뒤에 건수만큼 이어 붙는다.
 *
 * <pre>
 * 오프셋  길이  필드           타입      예시
 *   0     3    seq           NUMERIC  "001"  (일련번호)
 *   3     8    txDate        TEXT     "20260603"
 *  11     4    txType        TEXT     "출금"/"입금"  ← 한글 2자 = EUC-KR 4byte
 *  15    12    amount        NUMERIC  "000000050000"
 *  27    12    balanceAfter  NUMERIC  "000006829445"
 *  39    16    summary       TEXT     "카드결제"  ← 한글, EUC-KR 최대 8자
 *  ---------------------------------------------------------
 *  총 55 byte
 * </pre>
 *
 * txType·summary가 한글이라, 레코드 슬라이스도 byte 오프셋으로 잘라야 EUC-KR 2byte 문자가 안 깨진다.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRecord {

	/** 일련번호. */
	@Field(order = 1, length = 3, type = FieldType.NUMERIC)
	private String seq;

	/** 거래일자(YYYYMMDD). */
	@Field(order = 2, length = 8, type = FieldType.TEXT)
	private String txDate;

	/** 거래구분(한글 2자, EUC-KR 4byte). "입금"/"출금". */
	@Field(order = 3, length = 4, type = FieldType.TEXT)
	private String txType;

	/** 거래금액(원, 숫자). */
	@Field(order = 4, length = 12, type = FieldType.NUMERIC)
	private String amount;

	/** 거래 후 잔액(원, 숫자). */
	@Field(order = 5, length = 12, type = FieldType.NUMERIC)
	private String balanceAfter;

	/** 적요(한글, EUC-KR 최대 8자). */
	@Field(order = 6, length = 16, type = FieldType.TEXT)
	private String summary;
}
