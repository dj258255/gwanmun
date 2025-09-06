package io.gwanmun.ledger;

import io.gwanmun.core.CoreBankingClient;
import io.gwanmun.core.MockCoreBankingServer;
import io.gwanmun.gateway.GatewayException;
import io.gwanmun.gateway.GatewayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * <b>실제 소켓 타임아웃 → UNKNOWN</b>을 끝에서 끝까지 검증한다. 목업 계정계를 지연 모드로 두고
 * (요청은 정상 수신·처리하되 응답만 늦게), 클라이언트 read 타임아웃을 그보다 짧게 잡아 진짜
 * {@link SocketTimeoutException}을 일으킨 뒤, 3값 판정이 UNKNOWN을 내는지 본다.
 */
class TimeoutClassificationTest {

	@Test
	@DisplayName("지연 모드 계좌: 응답이 read 타임아웃보다 늦으면 진짜 타임아웃이 나고, 판정은 UNKNOWN")
	void realSocketTimeoutIsUnknown() throws Exception {
		// 서버는 1500ms 지연, 클라이언트 read 타임아웃은 500ms — 반드시 타임아웃이 난다.
		try (MockCoreBankingServer server = new MockCoreBankingServer(0, 1500)) {
			server.start();
			CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 1000, 500);
			GatewayService gateway = new GatewayService(client);

			GatewayException e = catchThrowableOfType(
					() -> gateway.balanceInquiry(MockCoreBankingServer.DELAY_ACCOUNT),
					GatewayException.class);

			assertThat(e).isNotNull();
			// 원인 사슬 어딘가에 SocketTimeoutException이 실제로 있다(가짜 예외가 아니라 소켓의 것).
			Throwable cause = e;
			boolean sawTimeout = false;
			while (cause != null) {
				if (cause instanceof SocketTimeoutException) {
					sawTimeout = true;
					break;
				}
				cause = cause.getCause();
			}
			assertThat(sawTimeout).as("진짜 SocketTimeoutException이 원인이어야 한다").isTrue();

			// 3값 판정: 타임아웃은 FAILED가 아니라 UNKNOWN이다.
			assertThat(TransactionStatus.ofFailure(e)).isEqualTo(TransactionStatus.UNKNOWN);
			client.close();
		}
	}

	@Test
	@DisplayName("같은 서버라도 일반 계좌는 지연 없이 정상 응답(SUCCESS 판정 경로)")
	void normalAccountStillFast() throws Exception {
		try (MockCoreBankingServer server = new MockCoreBankingServer(0, 1500)) {
			server.start();
			CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 1000, 500);
			GatewayService gateway = new GatewayService(client);

			var result = gateway.balanceInquiry("12345678901234");
			assertThat(TransactionStatus.ofResponseCode(result.response().getResponseCode()))
					.isEqualTo(TransactionStatus.SUCCESS);
			client.close();
		}
	}
}
