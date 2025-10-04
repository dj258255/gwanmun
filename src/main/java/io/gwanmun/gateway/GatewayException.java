package io.gwanmun.gateway;

/**
 * 게이트웨이 왕복 중 계정계 쪽에서 난 실패 — 연결 불가·타임아웃·응답 없음, 그리고 <b>왕복은 성공했지만
 * 응답 전문이 스펙과 다른 경우</b>(Phase 7)까지 포함한다. 요청 입력 오류(400)와 구분해, 호출 측이
 * 상태코드를 달리 주고 원장 기록을 빠뜨리지 않게 하는 계약 타입이다.
 *
 * <p>단, 내부 커넥션 풀 고갈({@code PoolExhaustedException})은 여기 담지 않는다 — 그건 계정계 실패가
 * 아니라 게이트웨이 내부 사정이라, 타입 그대로 올라가 503으로 따로 처리된다.
 */
public class GatewayException extends RuntimeException {

	public GatewayException(String message, Throwable cause) {
		super(message, cause);
	}
}
