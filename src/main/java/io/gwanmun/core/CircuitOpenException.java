package io.gwanmun.core;

import java.io.IOException;

/**
 * 서킷이 열려 있어 계정계 호출을 시도조차 하지 않고 즉시 거절했음을 알린다 (Phase 6).
 *
 * <p>{@link IOException}의 하위 타입이지만 <b>타임아웃·EOF와 성격이 다르다</b> — 요청이 밖으로
 * 나가지 않았으므로 계정계에서 처리됐을 가능성이 0이다. 그래서 원장 3값 판정에서는 UNKNOWN이 아니라
 * <b>FAILED</b>로 떨어진다({@code TransactionStatus.ofFailure}가 타임아웃/EOF만 UNKNOWN으로 본다).
 */
public class CircuitOpenException extends IOException {

	private final String circuitName;

	public CircuitOpenException(String circuitName, String message) {
		super(message);
		this.circuitName = circuitName;
	}

	public String circuitName() {
		return circuitName;
	}
}
