package io.gwanmun.ledger;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * 거래의 3값 상태. 2값(성공/실패)이 아니라 3값인 것이 이 모듈의 요점이다.
 *
 * <ul>
 *   <li>{@link #SUCCESS} — 계정계 응답을 정상 수신했고 응답코드도 정상("0000").</li>
 *   <li>{@link #FAILED} — 명확한 실패. 계정계가 오류 응답을 줬거나(없는 계좌 등), 입력 자체가 틀렸거나,
 *       요청이 계정계에 <b>도달하기 전에</b> 실패했다(연결 거부·풀 고갈). 계정계에서 처리됐을 가능성이 없다.</li>
 *   <li>{@link #UNKNOWN} — <b>응답을 못 받았다.</b> 요청은 나갔는데 타임아웃이 났거나, 응답 없이 연결이
 *       끊겼다. 계정계에서는 처리됐을 수도, 안 됐을 수도 있다. 이걸 임의로 FAILED로 적으면
 *       "실패인 줄 알고 재시도했더니 이중 거래"가 된다 — 그래서 모른다고 정직하게 적는다.</li>
 * </ul>
 */
public enum TransactionStatus {

	SUCCESS,
	FAILED,
	UNKNOWN;

	/** 정상 응답코드(계정계 관례). 이 코드면 SUCCESS, 다른 코드는 명확한 오류 응답이므로 FAILED. */
	private static final String OK_RESPONSE_CODE = "0000";

	/** 원인 사슬 순회 상한(순환 방어). */
	private static final int MAX_CAUSE_DEPTH = 10;

	/** 응답을 받은 경우의 판정 — 응답코드가 정상이면 SUCCESS, 오류 코드면 FAILED. */
	public static TransactionStatus ofResponseCode(String responseCode) {
		return OK_RESPONSE_CODE.equals(responseCode) ? SUCCESS : FAILED;
	}

	/**
	 * 응답을 못 받고 예외로 끝난 경우의 판정. 원인 사슬을 훑어 <b>"요청이 나간 뒤"</b>의 실패인지 본다.
	 *
	 * <ul>
	 *   <li>{@link SocketTimeoutException} — 요청은 보냈는데 정해진 시간 안에 응답이 안 왔다 → UNKNOWN.</li>
	 *   <li>{@link EOFException} — 요청은 보냈는데 응답 전문 없이 연결이 닫혔다 → UNKNOWN.</li>
	 *   <li>{@link ConnectException} 등 나머지 — 연결 자체가 안 됐거나(요청이 나가기 전) 풀 고갈처럼
	 *       보내지도 못한 실패 → FAILED (계정계에서 처리됐을 가능성이 없다).</li>
	 * </ul>
	 */
	public static TransactionStatus ofFailure(Throwable failure) {
		Throwable t = failure;
		for (int depth = 0; t != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (t instanceof SocketTimeoutException || t instanceof EOFException) {
				return UNKNOWN;
			}
			t = t.getCause();
		}
		return FAILED;
	}
}
