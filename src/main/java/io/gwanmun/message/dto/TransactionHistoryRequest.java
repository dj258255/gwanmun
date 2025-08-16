package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래내역 조회 <b>요청</b> 전문 (학습용 합성 스펙 — 실제 은행 표준을 베끼지 않았다).
 *
 * <p>요청은 <b>고정길이(40byte)</b>다. 가변인 것은 응답(레코드 N건)이지, 요청이 아니다.
 * 다만 이 요청/응답은 앞에 <b>4byte ASCII 길이 헤더</b>를 붙여 소켓으로 오간다
 * ({@code io.gwanmun.core.LengthPrefixedFramer}). 길이 헤더는 전송 계층의 프레이밍이고,
 * 아래 필드는 그 안에 담기는 본문이다.
 *
 * <pre>
 * 오프셋  길이  필드          타입      예시
 *   0     4    messageType  TEXT     "0300"  (거래내역 조회 요청)
 *   4    14    accountNo    NUMERIC  "12345678901234"
 *  18     8    fromDate     TEXT     "20260601"
 *  26     8    toDate       TEXT     "20260630"
 *  34     3    reqCount     NUMERIC  "005"  (요청 최대 건수)
 *  37     3    filler       TEXT     공백 3칸
 *  ---------------------------------------------------------
 *  총 40 byte
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionHistoryRequest {

	/** 전문구분(요청). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 계좌번호(숫자, 좌측 제로패딩). */
	@Field(order = 2, length = 14, type = FieldType.NUMERIC)
	private String accountNo;

	/** 조회 시작일(YYYYMMDD). */
	@Field(order = 3, length = 8, type = FieldType.TEXT)
	private String fromDate;

	/** 조회 종료일(YYYYMMDD). */
	@Field(order = 4, length = 8, type = FieldType.TEXT)
	private String toDate;

	/** 요청 최대 건수(숫자). */
	@Field(order = 5, length = 3, type = FieldType.NUMERIC)
	private String reqCount;

	/** 예비(필러) 영역. 공백으로 채운다. */
	@Field(order = 6, length = 3, type = FieldType.TEXT)
	private String filler;
}
