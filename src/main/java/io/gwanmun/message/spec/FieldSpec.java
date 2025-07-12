package io.gwanmun.message.spec;

/**
 * 해석이 끝난 한 필드의 스펙. {@link Field} 어노테이션을 읽어 오프셋까지 확정한 불변 값이다.
 *
 * @param javaField 매핑되는 자바 필드(리플렉션으로 값을 읽고 쓴다)
 * @param name      필드 이름(= 자바 필드명)
 * @param offset    전문 내 시작 byte 오프셋(0부터)
 * @param length    byte 길이
 * @param type      타입(패딩·정렬 관례 결정)
 */
public record FieldSpec(
		java.lang.reflect.Field javaField,
		String name,
		int offset,
		int length,
		FieldType type
) {
	/** 이 필드가 끝나는 지점(다음 필드의 시작 오프셋). */
	public int endOffset() {
		return offset + length;
	}
}
