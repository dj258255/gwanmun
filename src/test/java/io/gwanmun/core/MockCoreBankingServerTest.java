package io.gwanmun.core;

import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.dto.BalanceInquiryRequest;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.gwanmun.message.dto.NetCancelRequest;
import io.gwanmun.message.dto.NetCancelResponse;
import io.gwanmun.message.dto.TransactionStatusInquiryRequest;
import io.gwanmun.message.dto.TransactionStatusInquiryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 목업 계정계 TCP 서버와 클라이언트를 <b>실제 소켓</b>으로 붙여 검증한다.
 * 임의 포트(0)로 띄워 테스트 간 충돌을 피한다.
 */
class MockCoreBankingServerTest {

	private final MessageCodec codec = new MessageCodec();
	private MockCoreBankingServer server;

	@BeforeEach
	void startServer() throws IOException {
		server = new MockCoreBankingServer(0); // 임의의 빈 포트
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	private byte[] buildRequest(String accountNo) {
		return buildRequest(accountNo, "GWMNU20260709000000001");
	}

	private byte[] buildRequest(String accountNo, String tranId) {
		return codec.build(new BalanceInquiryRequest("0200", tranId, accountNo, "IN01", ""));
	}

	@Test
	@DisplayName("정상 왕복: 요청 전문 52byte 보내면 응답 전문 61byte가 파싱되어 잔액이 채워진다")
	void happyRoundTrip() throws IOException {
		CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 2000, 3000);

		byte[] responseFrame = client.exchange(buildRequest("12345678901234"));
		assertThat(responseFrame).hasSize(61);

		BalanceInquiryResponse res = codec.parse(responseFrame, BalanceInquiryResponse.class);
		assertThat(res.getMessageType()).isEqualTo("0210");
		assertThat(res.getResponseCode()).isEqualTo("0000");
		assertThat(res.getResponseMessage()).isEqualTo("정상 처리되었습니다");
		assertThat(res.getBalance()).matches("\\d+"); // 숫자 잔액
		assertThat(Long.parseLong(res.getBalance())).isPositive();
	}

	@Test
	@DisplayName("같은 계좌는 항상 같은 잔액(결정론적)")
	void deterministicBalance() throws IOException {
		CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 2000, 3000);

		BalanceInquiryResponse first = codec.parse(client.exchange(buildRequest("98765432109876")),
				BalanceInquiryResponse.class);
		BalanceInquiryResponse second = codec.parse(client.exchange(buildRequest("98765432109876")),
				BalanceInquiryResponse.class);

		assertThat(second.getBalance()).isEqualTo(first.getBalance());
	}

	@Test
	@DisplayName("없는 계좌(0): 응답코드 0001·계좌 없음 메시지")
	void notFoundAccount() throws IOException {
		CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 2000, 3000);

		BalanceInquiryResponse res = codec.parse(client.exchange(buildRequest("0")),
				BalanceInquiryResponse.class);
		assertThat(res.getResponseCode()).isEqualTo("0001");
		assertThat(res.getResponseMessage()).isEqualTo("없는 계좌입니다");
	}

	@Test
	@DisplayName("서버측 partial read: 요청 52byte를 20+32로 쪼개 보내도 정상 재조립되어 응답이 온다")
	void serverReassemblesSplitRequest() throws IOException, InterruptedException {
		byte[] request = buildRequest("11112222333344");

		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 2000);
			socket.setSoTimeout(3000);
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			// 전문을 일부러 두 번에 나눠 보낸다(TCP에서 흔한 상황을 강제 재현).
			out.write(request, 0, 20);
			out.flush();
			Thread.sleep(80); // 앞부분만 먼저 도착하도록 시간차를 준다
			out.write(request, 20, 32);
			out.flush();

			// 서버가 반쪽을 누적해 한 전문으로 조립하고 61byte 응답을 보내야 한다.
			byte[] response = readExactly(in, 61);
			BalanceInquiryResponse res = codec.parse(response, BalanceInquiryResponse.class);
			assertThat(res.getResponseCode()).isEqualTo("0000");
			assertThat(res.getAccountNo()).isEqualTo("11112222333344");
		}
	}

	@Test
	@DisplayName("keep-alive: 한 연결로 요청 3건을 연속 보내면 응답 3건이 순서대로 온다")
	void multipleRequestsOnOneConnection() throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 2000);
			socket.setSoTimeout(3000);
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			String[] accounts = {"10000000000001", "20000000000002", "30000000000003"};
			for (String acct : accounts) {
				out.write(buildRequest(acct));
				out.flush();
				byte[] response = readExactly(in, 61);
				BalanceInquiryResponse res = codec.parse(response, BalanceInquiryResponse.class);
				assertThat(res.getAccountNo()).isEqualTo(acct);
				assertThat(res.getResponseCode()).isEqualTo("0000");
			}
		}
	}

	@Test
	@DisplayName("동시 10건: 여러 연결이 붙어도 각자 자기 계좌의 응답을 받는다")
	void concurrentClients() throws Exception {
		int n = 10;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				String acct = String.format("%014d", 100 + i);
				results.add(pool.submit(() -> {
					CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 2000, 3000);
					BalanceInquiryResponse res = codec.parse(client.exchange(buildRequest(acct)),
							BalanceInquiryResponse.class);
					return res.getAccountNo().equals(String.valueOf(Long.parseLong(acct)))
							&& res.getResponseCode().equals("0000");
				}));
			}
			for (Future<Boolean> f : results) {
				assertThat(f.get(5, TimeUnit.SECONDS)).isTrue();
			}
		} finally {
			pool.shutdownNow();
		}
	}

	// ------------------------------------------------------------------
	// Phase 6 — 계정계 인메모리 원장 · 거래상태조회 · 망취소
	// ------------------------------------------------------------------

	private byte[] buildStatusInquiry(String origTranId) {
		return codec.build(new TransactionStatusInquiryRequest("0400", origTranId, "ST01", ""));
	}

	private byte[] buildNetCancel(String origTranId) {
		return codec.build(new NetCancelRequest("0420", origTranId, "NC01", ""));
	}

	@Test
	@DisplayName("거래상태조회: 처리한 거래ID는 처리됨(01), 모르는 거래ID는 미처리(02)를 답한다")
	void statusInquiryAnswersFromProcessedLedger() throws IOException {
		CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 2000, 3000);
		String tranId = "GWMNU20260709000000101";

		// 원거래(잔액조회)를 먼저 처리시킨다 — 계정계가 이 거래ID를 기억해야 한다.
		client.exchange(buildRequest("12345678901234", tranId));
		assertThat(server.hasProcessed(tranId)).isTrue();

		TransactionStatusInquiryResponse known = codec.parse(
				client.exchange(buildStatusInquiry(tranId)), TransactionStatusInquiryResponse.class);
		assertThat(known.getMessageType()).isEqualTo("0410");
		assertThat(known.getProcessedFlag()).isEqualTo(TransactionStatusInquiryResponse.PROCESSED);
		assertThat(known.getResponseMessage()).isEqualTo("처리된 거래입니다");

		TransactionStatusInquiryResponse unknown = codec.parse(
				client.exchange(buildStatusInquiry("GWMNU20260709999999999")),
				TransactionStatusInquiryResponse.class);
		assertThat(unknown.getProcessedFlag()).isEqualTo(TransactionStatusInquiryResponse.NOT_PROCESSED);
		assertThat(unknown.getResponseMessage()).isEqualTo("미처리 거래입니다");
	}

	@Test
	@DisplayName("망취소: 처리한 원거래는 취소 성공(01, 멱등), 모르는 원거래는 원거래 없음(02)")
	void netCancelCancelsProcessedTransaction() throws IOException {
		CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 2000, 3000);
		String tranId = "GWMNU20260709000000102";
		client.exchange(buildRequest("12345678901234", tranId));

		NetCancelResponse first = codec.parse(
				client.exchange(buildNetCancel(tranId)), NetCancelResponse.class);
		assertThat(first.getMessageType()).isEqualTo("0430");
		assertThat(first.getCancelResult()).isEqualTo(NetCancelResponse.CANCELED);
		assertThat(first.getResponseMessage()).isEqualTo("취소 완료되었습니다");

		// 같은 취소를 다시 보내도 성공(멱등) — 망취소 자체가 응답 유실로 재발사될 수 있기 때문.
		NetCancelResponse again = codec.parse(
				client.exchange(buildNetCancel(tranId)), NetCancelResponse.class);
		assertThat(again.getCancelResult()).isEqualTo(NetCancelResponse.CANCELED);

		NetCancelResponse missing = codec.parse(
				client.exchange(buildNetCancel("GWMNU20260709999999998")), NetCancelResponse.class);
		assertThat(missing.getCancelResult()).isEqualTo(NetCancelResponse.ORIGINAL_NOT_FOUND);
		assertThat(missing.getResponseMessage()).isEqualTo("원거래가 없습니다");
	}

	@Test
	@DisplayName("지연 모드: 클라이언트가 타임아웃으로 포기해도 계정계에는 처리 기록이 남는다(→ 상태조회 처리됨)")
	void delayAccountIsRecordedBeforeResponding() throws IOException {
		// 서버 지연 800ms > read 타임아웃 200ms — 반드시 타임아웃.
		server.close();
		server = new MockCoreBankingServer(0, 800);
		server.start();
		CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 1000, 200);
		String tranId = "GWMNU20260709000000103";

		assertThatThrownBy(() -> client.exchange(buildRequest(MockCoreBankingServer.DELAY_ACCOUNT, tranId)))
				.isInstanceOf(IOException.class);

		// 게이트웨이는 응답을 못 받았지만(UNKNOWN 상황), 계정계는 처리했다 — 상태조회가 그걸 답한다.
		assertThat(server.hasProcessed(tranId)).isTrue();
		TransactionStatusInquiryResponse status = codec.parse(
				client.exchange(buildStatusInquiry(tranId)), TransactionStatusInquiryResponse.class);
		assertThat(status.getProcessedFlag()).isEqualTo(TransactionStatusInquiryResponse.PROCESSED);
	}

	@Test
	@DisplayName("수신 유실 모드: 기록도 응답도 없이 끊는다(EOF) → 상태조회는 미처리를 답한다")
	void dropAccountLeavesNoRecord() throws IOException {
		CoreBankingClient client = new CoreBankingClient("127.0.0.1", server.port(), 2000, 3000);
		String tranId = "GWMNU20260709000000104";

		assertThatThrownBy(() -> client.exchange(buildRequest(MockCoreBankingServer.DROP_ACCOUNT, tranId)))
				.isInstanceOf(EOFException.class);

		assertThat(server.hasProcessed(tranId)).isFalse();
		TransactionStatusInquiryResponse status = codec.parse(
				client.exchange(buildStatusInquiry(tranId)), TransactionStatusInquiryResponse.class);
		assertThat(status.getProcessedFlag()).isEqualTo(TransactionStatusInquiryResponse.NOT_PROCESSED);
	}

	/** 프레이밍 없이 테스트가 직접 정확히 len byte를 읽는 헬퍼(서버 응답 확인용). */
	private static byte[] readExactly(InputStream in, int len) throws IOException {
		byte[] buf = new byte[len];
		int off = 0;
		while (off < len) {
			int r = in.read(buf, off, len - off);
			if (r < 0) {
				throw new IOException("응답이 " + off + "byte에서 끊겼습니다(기대 " + len + "byte).");
			}
			off += r;
		}
		return buf;
	}
}
