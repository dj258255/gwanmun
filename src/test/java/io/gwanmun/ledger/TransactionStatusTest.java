package io.gwanmun.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import io.gwanmun.core.CircuitOpenException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3값 상태 판정을 못 박는다. 핵심은 <b>타임아웃 = FAILED가 아니라 UNKNOWN</b>이라는 것 —
 * 응답을 못 받았을 뿐, 계정계에서 처리됐을 수 있다.
 */
class TransactionStatusTest {

	@Test
	@DisplayName("응답을 받았고 응답코드 0000 → SUCCESS")
	void okResponse() {
		assertThat(TransactionStatus.ofResponseCode("0000")).isEqualTo(TransactionStatus.SUCCESS);
	}

	@Test
	@DisplayName("응답을 받았지만 오류 코드(없는 계좌 0001 등) → 명확한 실패, FAILED")
	void errorResponse() {
		assertThat(TransactionStatus.ofResponseCode("0001")).isEqualTo(TransactionStatus.FAILED);
		assertThat(TransactionStatus.ofResponseCode(null)).isEqualTo(TransactionStatus.FAILED);
	}

	@Test
	@DisplayName("read 타임아웃 → 응답을 못 받았을 뿐이므로 UNKNOWN (임의로 실패 처리하지 않는다)")
	void timeoutIsUnknown() {
		assertThat(TransactionStatus.ofFailure(new SocketTimeoutException("Read timed out")))
				.isEqualTo(TransactionStatus.UNKNOWN);
		// 게이트웨이 예외로 몇 겹 감싸여 와도 원인 사슬을 훑어 같은 판정.
		Throwable wrapped = new RuntimeException("통신 실패",
				new IOException("read 실패", new SocketTimeoutException("Read timed out")));
		assertThat(TransactionStatus.ofFailure(wrapped)).isEqualTo(TransactionStatus.UNKNOWN);
	}

	@Test
	@DisplayName("요청은 보냈는데 응답 없이 연결이 닫힘(EOF) → UNKNOWN")
	void eofIsUnknown() {
		Throwable wrapped = new RuntimeException("통신 실패",
				new EOFException("계정계가 응답 전문 없이 연결을 닫았습니다."));
		assertThat(TransactionStatus.ofFailure(wrapped)).isEqualTo(TransactionStatus.UNKNOWN);
	}

	@Test
	@DisplayName("연결 자체가 거부됨(요청이 나가기 전) → 처리됐을 가능성이 없으므로 FAILED")
	void connectRefusedIsFailed() {
		Throwable wrapped = new RuntimeException("통신 실패", new ConnectException("Connection refused"));
		assertThat(TransactionStatus.ofFailure(wrapped)).isEqualTo(TransactionStatus.FAILED);
	}

	@Test
	@DisplayName("원인 불명의 일반 예외·null → 보수적으로 FAILED")
	void genericIsFailed() {
		assertThat(TransactionStatus.ofFailure(new IllegalStateException("boom")))
				.isEqualTo(TransactionStatus.FAILED);
		assertThat(TransactionStatus.ofFailure(null)).isEqualTo(TransactionStatus.FAILED);
	}

	@Test
	@DisplayName("서킷 OPEN 즉시 거절 → 요청이 나가지도 않았으므로 UNKNOWN이 아니라 FAILED (Phase 6)")
	void circuitOpenIsFailed() {
		// IOException 계열이지만 계정계에서 처리됐을 가능성이 0인 실패 — 3값 판정이 이를 구분해야 한다.
		Throwable wrapped = new RuntimeException("통신 실패",
				new CircuitOpenException("core-banking", "서킷 OPEN — 즉시 실패"));
		assertThat(TransactionStatus.ofFailure(wrapped)).isEqualTo(TransactionStatus.FAILED);
	}
}
