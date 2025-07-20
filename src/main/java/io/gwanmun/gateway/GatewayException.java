package io.gwanmun.gateway;

/**
 * 게이트웨이 왕복 중 계정계 통신 단계에서 난 실패(연결 불가·타임아웃·응답 없음 등).
 * 파싱/빌드 예외(전문 자체 문제)와 구분해, 호출 측이 상태코드를 달리 줄 수 있게 한다(502 vs 400).
 */
public class GatewayException extends RuntimeException {

	public GatewayException(String message, Throwable cause) {
		super(message, cause);
	}
}
