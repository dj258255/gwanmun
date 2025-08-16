package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래내역 조회 <b>응답</b> 전문의 <b>고정 헤더</b> (학습용 합성 스펙).
 *
 * <p>응답 본문은 <b>이 고정 헤더(30byte) + 거래 레코드 N건({@link TransactionRecord}, 각 55byte)</b>으로
 * 이뤄진다. 헤더가 <b>건수(recordCount)</b>와 <b>전체 본문 길이(totalLength)</b>를 담아
 * "뒤에 몇 건이 얼마만큼 붙는지"를 스스로 설명한다(self-describing). 그래서 파서는 헤더만 읽고도
 * 레코드 경계를 계산할 수 있다.
 *
 * <pre>
 * 오프셋  길이  필드          타입      예시
 *   0     4    messageType  TEXT     "0310"  (거래내역 조회 응답)
 *   4    14    accountNo    NUMERIC  "12345678901234"
 *  18     3    recordCount  NUMERIC  "005"  (뒤따르는 레코드 건수)
 *  21     5    totalLength  NUMERIC  "00305" (헤더+레코드 전체 본문 byte 길이)
 *  26     4    responseCode TEXT     "0000"
 *  ---------------------------------------------------------
 *  총 30 byte (레코드는 이 뒤에 recordCount건 반복)
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionHistoryHeader {

	/** 전문구분(응답). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 계좌번호(숫자, 좌측 제로패딩). */
	@Field(order = 2, length = 14, type = FieldType.NUMERIC)
	private String accountNo;

	/** 뒤따르는 거래 레코드 건수. */
	@Field(order = 3, length = 3, type = FieldType.NUMERIC)
	private String recordCount;

	/** 헤더+레코드를 합친 전체 본문 byte 길이(길이 헤더 4byte는 제외). */
	@Field(order = 4, length = 5, type = FieldType.NUMERIC)
	private String totalLength;

	/** 응답코드. "0000"=정상. */
	@Field(order = 5, length = 4, type = FieldType.TEXT)
	private String responseCode;
}
