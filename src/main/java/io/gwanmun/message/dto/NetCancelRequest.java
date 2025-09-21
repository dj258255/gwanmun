package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 망취소(網取消) <b>요청</b> 전문 (Phase 6 — UNKNOWN 해소의 2단계).
 *
 * <p>상태조회로 "계정계가 처리했음"이 확인된 UNKNOWN 거래를 원천 무효화한다 — 게이트웨이는 결과를
 * 호출자에게 못 돌려줬으므로, 계정계에 남은 원거래를 취소해 양쪽 장부를 다시 맞춘다. 오픈뱅킹의
 * 망취소(타임아웃 등으로 결과를 못 받은 원거래의 취소 요청) 관례를 참조했다.
 *
 * <p><b>이 전문은 변경성(MUTATION)이다 — 재시도 금지.</b> 조회와 달리 계정계 상태를 바꾸므로,
 * 응답을 못 받았다고 자동 재전송하면 안 된다(취소 자체가 또 UNKNOWN이 되면 상태조회부터 다시).
 *
 * <pre>
 * 오프셋  길이  필드            타입      예시
 *   0     4    messageType    TEXT     "0420"  (망취소 요청)
 *   4    22    origTranId     TEXT     "GWMNU20260709000000001"  (취소할 원거래)
 *  26     4    txCode         TEXT     "NC01"
 *  30    22    filler         TEXT     공백 22칸
 *  ---------------------------------------------------------
 *  총 52 byte
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NetCancelRequest {

	/** 전문구분(망취소 요청). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 취소할 원거래의 거래고유번호. */
	@Field(order = 2, length = 22, type = FieldType.TEXT)
	private String origTranId;

	/** 거래코드. */
	@Field(order = 3, length = 4, type = FieldType.TEXT)
	private String txCode;

	/** 예비(필러) 영역. */
	@Field(order = 4, length = 22, type = FieldType.TEXT)
	private String filler;
}
