package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 잔액조회 <b>요청</b> 전문 (학습용 합성 스펙 — 실제 은행 표준을 베끼지 않았다).
 *
 * <p><b>Phase 6에서 거래고유번호 필드를 추가했다(30 → 52 byte).</b> Phase 5까지의 요청 전문에는
 * 게이트웨이가 채번한 거래고유번호가 실리지 않아, 계정계와 게이트웨이가 <b>같은 거래를 가리킬 공통
 * 열쇠가 없었다.</b> 응답을 못 받은 거래(UNKNOWN)를 나중에 거래상태조회·망취소로 확정 지으려면,
 * 원거래 전문 자체에 그 열쇠를 실어야 한다 — 오픈뱅킹 전문이 bank_tran_id를 본문에 싣는 것과 같은
 * 이유다. 그래서 요청 전문 공통 선두를 "전문구분(4) + 거래고유번호(22)"로 맞췄다(상태조회·망취소
 * 전문도 같은 선두 구조).
 *
 * <pre>
 * 오프셋  길이  필드            타입      예시
 *   0     4    messageType    TEXT     "0200"  (요청 전문구분)
 *   4    22    tranId         TEXT     "GWMNU20260709000000001"  (거래고유번호)
 *  26    14    accountNo      NUMERIC  "12345678901234"
 *  40     4    txCode         TEXT     "IN01"  (거래코드)
 *  44     8    filler         TEXT     공백 8칸  (예비 영역)
 *  ---------------------------------------------------------
 *  총 52 byte
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceInquiryRequest {

	/** 전문구분(요청). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 게이트웨이가 채번한 거래고유번호(22자). 계정계가 이 열쇠로 거래를 기억한다(상태조회·망취소의 근거). */
	@Field(order = 2, length = 22, type = FieldType.TEXT)
	private String tranId;

	/** 계좌번호(숫자, 좌측 제로패딩). */
	@Field(order = 3, length = 14, type = FieldType.NUMERIC)
	private String accountNo;

	/** 거래코드. */
	@Field(order = 4, length = 4, type = FieldType.TEXT)
	private String txCode;

	/** 예비(필러) 영역. 공백으로 채운다. */
	@Field(order = 5, length = 8, type = FieldType.TEXT)
	private String filler;
}
