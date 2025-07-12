package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 잔액조회 <b>응답</b> 전문 (학습용 합성 스펙 — 실제 은행 표준을 베끼지 않았다).
 *
 * <pre>
 * 오프셋  길이  필드              타입      예시
 *   0     4    messageType      TEXT     "0210"  (응답 전문구분)
 *   4    14    accountNo        NUMERIC  "12345678901234"
 *  18     4    txCode           TEXT     "IN01"
 *  22    15    balance          NUMERIC  "000000001234567"
 *  37     4    responseCode     TEXT     "0000"  (정상)
 *  41    20    responseMessage  TEXT     "정상 처리되었습니다"  ← 한글, EUC-KR 2byte/자
 *  ---------------------------------------------------------
 *  총 61 byte
 * </pre>
 *
 * responseMessage는 length=20이지만 <b>문자 20자가 아니라 20 byte</b>다.
 * 한글은 EUC-KR에서 2byte라 최대 한글 10자까지 담긴다(함정의 핵심).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceInquiryResponse {

	/** 전문구분(응답). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 계좌번호(숫자, 좌측 제로패딩). */
	@Field(order = 2, length = 14, type = FieldType.NUMERIC)
	private String accountNo;

	/** 거래코드. */
	@Field(order = 3, length = 4, type = FieldType.TEXT)
	private String txCode;

	/** 잔액(숫자, 좌측 제로패딩). 원 단위 정수. */
	@Field(order = 4, length = 15, type = FieldType.NUMERIC)
	private String balance;

	/** 응답코드. "0000"=정상. */
	@Field(order = 5, length = 4, type = FieldType.TEXT)
	private String responseCode;

	/** 응답 메시지(한글, EUC-KR). 최대 20 byte = 한글 10자. */
	@Field(order = 6, length = 20, type = FieldType.TEXT)
	private String responseMessage;
}
