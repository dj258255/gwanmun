package io.gwanmun.message.spec;

/**
 * 전문 필드의 타입. 타입이 곧 패딩·정렬 관례를 결정한다(금융 고정길이 전문의 오래된 관습).
 *
 * <ul>
 *   <li>{@link #NUMERIC} — 숫자. 우측 정렬 + 좌측 제로('0', 0x30) 패딩. 예: 잔액 "0000000123456".</li>
 *   <li>{@link #TEXT} — 문자(한글 포함). 좌측 정렬 + 우측 공백(' ', 0x20) 패딩. 예: 메시지 "정상   ".</li>
 * </ul>
 *
 * 이 둘을 혼동하면 파싱이 통째로 어긋난다. 그래서 타입을 스펙에 못 박는다.
 */
public enum FieldType {
	NUMERIC,
	TEXT
}
