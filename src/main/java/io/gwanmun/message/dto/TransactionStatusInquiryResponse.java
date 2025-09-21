package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래상태조회 <b>응답</b> 전문 (Phase 6).
 *
 * <p>{@code processedFlag}가 이 전문의 요점이다 — 계정계가 원거래를 <b>처리했으면 "01"</b>,
 * <b>받은 적 없으면 "02"</b>. UNKNOWN 거래의 운명은 이 두 글자로 갈린다(처리됨 → 망취소로 무효화,
 * 미처리 → FAILED 확정).
 *
 * <pre>
 * 오프셋  길이  필드              타입      예시
 *   0     4    messageType      TEXT     "0410"  (상태조회 응답)
 *   4    22    origTranId       TEXT     "GWMNU20260709000000001"
 *  26     4    txCode           TEXT     "ST01"
 *  30     2    processedFlag    TEXT     "01"=처리됨 / "02"=미처리
 *  32     4    responseCode     TEXT     "0000"
 *  36    20    responseMessage  TEXT     "처리된 거래입니다"  (한글, EUC-KR 2byte/자)
 *  56     5    filler           TEXT     공백 5칸
 *  ---------------------------------------------------------
 *  총 61 byte (모든 응답 전문과 동일 — 같은 소켓·같은 프레이밍을 탄다)
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionStatusInquiryResponse {

	/** 원거래가 계정계에서 처리됐음. */
	public static final String PROCESSED = "01";
	/** 원거래를 계정계가 받은 적 없음(미처리). */
	public static final String NOT_PROCESSED = "02";

	/** 전문구분(상태조회 응답). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 원거래의 거래고유번호(요청을 그대로 반사). */
	@Field(order = 2, length = 22, type = FieldType.TEXT)
	private String origTranId;

	/** 거래코드. */
	@Field(order = 3, length = 4, type = FieldType.TEXT)
	private String txCode;

	/** 처리 여부: "01"=처리됨 / "02"=미처리. */
	@Field(order = 4, length = 2, type = FieldType.TEXT)
	private String processedFlag;

	/** 응답코드. "0000"=조회 자체는 정상. */
	@Field(order = 5, length = 4, type = FieldType.TEXT)
	private String responseCode;

	/** 응답 메시지(한글, EUC-KR). 최대 20 byte = 한글 10자. */
	@Field(order = 6, length = 20, type = FieldType.TEXT)
	private String responseMessage;

	/** 예비(필러) 영역. */
	@Field(order = 7, length = 5, type = FieldType.TEXT)
	private String filler;
}
