package io.gwanmun.core;

/**
 * 계정계로 나가는 거래의 성격 — <b>재시도해도 되는가</b>를 가르는 기준 (Phase 6).
 *
 * <p>재시도는 공짜가 아니다. 응답을 못 받은 거래는 계정계에서 <b>이미 처리됐을 수 있으므로</b>,
 * 상태를 바꾸는 거래를 자동 재전송하면 같은 거래가 두 번 실행된다(이중 거래). 그래서 재시도 허용
 * 여부는 옵션이 아니라 거래의 성격에 못 박는다.
 */
public enum TransactionKind {

	/**
	 * 조회성 — 잔액조회·거래내역·거래상태조회처럼 계정계 상태를 바꾸지 않는 거래.
	 * 몇 번을 다시 보내도 결과가 같으므로 <b>제한적 재시도(지수 백오프)</b>를 허용한다.
	 */
	INQUIRY(true),

	/**
	 * 변경성 — 이체·망취소처럼 계정계 상태를 바꾸는 거래. <b>재시도 금지.</b>
	 * 응답을 못 받으면 UNKNOWN으로 남기고, 거래상태조회·망취소의 해소 절차로 확정 짓는다.
	 */
	MUTATION(false);

	private final boolean retryable;

	TransactionKind(boolean retryable) {
		this.retryable = retryable;
	}

	public boolean retryable() {
		return retryable;
	}
}
