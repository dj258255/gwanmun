package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 망취소 <b>응답</b> 전문 (Phase 6).
 *
 * <pre>
 * 오프셋  길이  필드              타입      예시
 *   0     4    messageType      TEXT     "0430"  (망취소 응답)
 *   4    22    origTranId       TEXT     "GWMNU20260709000000001"
 *  26     4    txCode           TEXT     "NC01"
 *  30     2    cancelResult     TEXT     "01"=취소 성공 / "02"=원거래 없음
 *  32     4    responseCode     TEXT     "0000"
 *  36    20    responseMessage  TEXT     "취소 완료되었습니다"  (한글, EUC-KR 2byte/자)
 *  56     5    filler           TEXT     공백 5칸
 *  ---------------------------------------------------------
 *  총 61 byte
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NetCancelResponse {

	/** 원거래를 찾아 취소했음(이미 취소된 원거래도 성공 — 멱등). */
	public static final String CANCELED = "01";
	/** 원거래가 계정계에 없음(취소할 것이 없다). */
	public static final String ORIGINAL_NOT_FOUND = "02";

	/** 전문구분(망취소 응답). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 취소 대상 원거래의 거래고유번호(요청을 그대로 반사). */
	@Field(order = 2, length = 22, type = FieldType.TEXT)
	private String origTranId;

	/** 거래코드. */
	@Field(order = 3, length = 4, type = FieldType.TEXT)
	private String txCode;

	/** 취소 결과: "01"=취소 성공 / "02"=원거래 없음. */
	@Field(order = 4, length = 2, type = FieldType.TEXT)
	private String cancelResult;

	/** 응답코드. */
	@Field(order = 5, length = 4, type = FieldType.TEXT)
	private String responseCode;

	/** 응답 메시지(한글, EUC-KR). 최대 20 byte = 한글 10자. */
	@Field(order = 6, length = 20, type = FieldType.TEXT)
	private String responseMessage;

	/** 예비(필러) 영역. */
	@Field(order = 7, length = 5, type = FieldType.TEXT)
	private String filler;
}
