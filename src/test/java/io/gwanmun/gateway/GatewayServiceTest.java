package io.gwanmun.gateway;

import io.gwanmun.gateway.GatewayService.GatewayResult;
import io.gwanmun.core.CoreBankingClient;
import io.gwanmun.core.MockCoreBankingServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 게이트웨이 배선을 목업 계정계와 실제 소켓으로 붙여 검증한다.
 * REST 계층만 빼고, "JSON → 요청 전문 → TCP 왕복 → 응답 전문 → JSON" 전 구간을 탄다.
 */
class GatewayServiceTest {

	private MockCoreBankingServer server;
	private GatewayService gateway;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockCoreBankingServer(0);
		server.start();
		CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 2000, 3000);
		gateway = new GatewayService(client);
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	@Test
	@DisplayName("왕복 결과에 오간 전문 바이트(요청 52 / 응답 61)와 파싱 JSON이 함께 담긴다")
	void roundTripCarriesFrames() {
		GatewayResult result = gateway.balanceInquiry("12345678901234", "GWMNU20260709000000001");

		assertThat(result.requestFrame()).hasSize(52);
		assertThat(result.responseFrame()).hasSize(61);
		assertThat(result.response().getResponseCode()).isEqualTo("0000");
		assertThat(result.response().getAccountNo()).isEqualTo("12345678901234");
		assertThat(Long.parseLong(result.response().getBalance())).isPositive();
		assertThat(result.corePort()).isEqualTo(server.port());
	}

	@Test
	@DisplayName("계정계가 죽어 있으면 GatewayException(통신 실패)로 감싼다")
	void wrapsConnectFailure() {
		server.close(); // 계정계 종료
		assertThatThrownBy(() -> gateway.balanceInquiry("12345678901234", "GWMNU20260709000000002"))
				.isInstanceOf(GatewayException.class)
				.hasMessageContaining("통신 실패");
	}
}
