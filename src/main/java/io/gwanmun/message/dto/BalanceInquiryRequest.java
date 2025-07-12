package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 잔액조회 <b>요청</b> 전문 (학습용 합성 스펙 — 실제 은행 표준을 베끼지 않았다).
 *
 * <pre>
 * 오프셋  길이  필드            타입      예시
 *   0     4    messageType    TEXT     "0200"  (요청 전문구분)
 *   4    14    accountNo      NUMERIC  "12345678901234"
 *  18     4    txCode         TEXT     "IN01"  (거래코드)
 *  22     8    filler         TEXT     공백 8칸  (예비 영역)
 *  ---------------------------------------------------------
 *  총 30 byte
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceInquiryRequest {

	/** 전문구분(요청). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 계좌번호(숫자, 좌측 제로패딩). */
	@Field(order = 2, length = 14, type = FieldType.NUMERIC)
	private String accountNo;

	/** 거래코드. */
	@Field(order = 3, length = 4, type = FieldType.TEXT)
	private String txCode;

	/** 예비(필러) 영역. 공백으로 채운다. */
	@Field(order = 4, length = 8, type = FieldType.TEXT)
	private String filler;
}
