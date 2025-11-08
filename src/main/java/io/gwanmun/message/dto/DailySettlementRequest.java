package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 당일 처리내역 전체 조회 <b>요청</b> 전문 (Phase 9 — EOD 대사의 계정계측 입력).
 *
 * <p>대사 배치가 "계정계, 이 날짜에 당신이 처리한 거래 전부를 주시오"라고 묻는다. 응답은 레코드
 * 건수만큼 길이가 달라지는 <b>가변 전문</b>이라 4byte 길이 헤더로 프레이밍한다(거래내역 조회와 같은
 * 길이 프리픽스 방식). 요청 자체는 고정 20byte.
 *
 * <pre>
 * 오프셋  길이  필드          타입      예시
 *   0     4    messageType  TEXT     "0500"  (당일 처리내역 조회 요청)
 *   4     8    settleDate   TEXT     "20260709"  (대사 기준일 YYYYMMDD)
 *  12     8    filler       TEXT     공백 8칸
 *  ---------------------------------------------------------
 *  총 20 byte
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailySettlementRequest {

	/** 전문구분(당일 처리내역 조회 요청). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 대사 기준일(YYYYMMDD). */
	@Field(order = 2, length = 8, type = FieldType.TEXT)
	private String settleDate;

	/** 예비(필러) 영역. 공백으로 채운다. */
	@Field(order = 3, length = 8, type = FieldType.TEXT)
	private String filler;
}
