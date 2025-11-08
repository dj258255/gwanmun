package io.gwanmun.message.dto;

import io.gwanmun.message.spec.Field;
import io.gwanmun.message.spec.FieldType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 당일 처리내역 조회 <b>응답</b>의 <b>고정 헤더</b> (Phase 9). 뒤에 {@link DailySettlementRecord}가
 * 건수만큼 붙는다. 헤더가 건수·전체 길이를 스스로 밝혀(self-describing) 파서가 경계를 계산한다.
 *
 * <pre>
 * 오프셋  길이  필드          타입      예시
 *   0     4    messageType  TEXT     "0510"  (당일 처리내역 조회 응답)
 *   4     8    settleDate   TEXT     "20260709"
 *  12     3    recordCount  NUMERIC  "003"   (뒤따르는 레코드 건수)
 *  15     5    totalLength  NUMERIC  "00174" (헤더+레코드 전체 본문 byte 길이)
 *  20     4    responseCode TEXT     "0000"
 *  ---------------------------------------------------------
 *  총 24 byte
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailySettlementHeader {

	/** 전문구분(응답). */
	@Field(order = 1, length = 4, type = FieldType.TEXT)
	private String messageType;

	/** 대사 기준일(YYYYMMDD). */
	@Field(order = 2, length = 8, type = FieldType.TEXT)
	private String settleDate;

	/** 뒤따르는 레코드 건수. */
	@Field(order = 3, length = 3, type = FieldType.NUMERIC)
	private String recordCount;

	/** 헤더+레코드를 합친 전체 본문 byte 길이(길이 헤더 4byte는 제외). */
	@Field(order = 4, length = 5, type = FieldType.NUMERIC)
	private String totalLength;

	/** 응답코드. "0000"=정상. */
	@Field(order = 5, length = 4, type = FieldType.TEXT)
	private String responseCode;
}
