package io.gwanmun.message.spec;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 전문 스펙을 DTO 필드에 선언하는 어노테이션.
 *
 * <p>하드코딩 파싱(전문마다 substring을 새로 짜는 방식)은 지옥이다 —
 * 필드가 하나 밀리면 뒤가 전부 깨지고, 눈으로 오프셋을 세야 한다.
 * 대신 레이아웃을 선언으로 남기면, 오프셋은 앞 필드들의 길이 합으로 <b>자동 계산</b>된다.
 *
 * <p>길이({@link #length()})는 <b>문자 수가 아니라 byte 수</b>다.
 * 한글 1자는 EUC-KR에서 2byte이므로, 한글 10자 필드는 length=20으로 선언한다(함정).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Field {

	/** 전문 내 필드 순서(1부터). 오프셋은 이 순서대로 앞 필드 길이를 누적해 계산한다. */
	int order();

	/** 필드가 차지하는 <b>byte</b> 길이(문자 수 아님). */
	int length();

	/** 필드 타입. 패딩·정렬 관례를 결정한다. 기본값은 문자. */
	FieldType type() default FieldType.TEXT;
}
