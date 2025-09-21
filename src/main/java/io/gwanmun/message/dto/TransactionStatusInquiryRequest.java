package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래상태조회 <b>요청</b> 전문 (Phase 6 — UNKNOWN 해소의 1단계).
 *
 * <p>응답을 못 받아 UNKNOWN으로 적힌 원거래에 대해 "계정계, 이 거래 처리했습니까?"를 묻는다.
 * 원거래의 거래고유번호가 유일한 열쇠다. 요청 전문 공통 선두(전문구분 4 + 거래고유번호 22)를
 * 잔액조회 요청과 똑같이 맞춰, 계정계가 같은 고정길이 프레이밍(52 byte)으로 받는다.
 *
 * <pre>
 * 오프셋  길이  필드            타입      예시
 *   0     4    messageType    TEXT     "0400"  (상태조회 요청)
 *   4    22    origTranId     TEXT     "GWMNU20260709000000001"  (원거래 거래고유번호)
 *  26     4    txCode         TEXT     "ST01"
 *  30    22    filler         TEXT     공백 22칸
 *  ---------------------------------------------------------
 *  총 52 byte (모든 요청 전문과 동일 — 같은 소켓·같은 프레이밍을 탄다)
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionStatusInquiryRequest {

	/** 전문구분(상태조회 요청). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 원거래의 거래고유번호 — 계정계가 자기 원장에서 이 열쇠로 찾는다. */
	@Field(order = 2, length = 22, type = FieldType.TEXT)
	private String origTranId;

	/** 거래코드. */
	@Field(order = 3, length = 4, type = FieldType.TEXT)
	private String txCode;

	/** 예비(필러) 영역. */
	@Field(order = 4, length = 22, type = FieldType.TEXT)
	private String filler;
}
