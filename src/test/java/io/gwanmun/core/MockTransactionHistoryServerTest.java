package io.gwanmun.core;

import io.gwanmun.core.TransactionHistoryClient.HistoryResult;
import io.gwanmun.message.dto.TransactionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 거래내역(가변 전문) 목업 서버와 클라이언트를 <b>실제 소켓</b>으로 붙여 검증한다.
 * 길이 프리픽스 프레이밍, 가변 레코드 왕복, 커넥션 풀 재사용을 실측한다.
 */
class MockTransactionHistoryServerTest {

	private MockTransactionHistoryServer server;

	@BeforeEach
	void startServer() throws IOException {
		server = new MockTransactionHistoryServer(0); // 임의의 빈 포트
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	@DisplayName("가변 왕복: 5건 요청하면 헤더 건수·전체길이가 실제 레코드와 일치")
	void variableRoundTrip() {
		try (TransactionHistoryClient client = new TransactionHistoryClient("127.0.0.1", server.port(), 2000, 3000)) {
			HistoryResult r = client.query("12345678901234", 5);

			assertThat(r.records()).hasSize(5);
			assertThat(r.header().getRecordCount()).isEqualTo("5");
			assertThat(r.header().getResponseCode()).isEqualTo("0000");
			// 응답 전선 = 4byte 길이 헤더 + 본문. 본문 길이 = 30(헤더) + 5*55(레코드) = 305.
			assertThat(r.responseWire()).hasSize(4 + 305);
			assertThat(r.header().getTotalLength()).isEqualTo("305");

			// 레코드의 한글 필드가 안 깨지고 왕복.
			TransactionRecord first = r.records().get(0);
			assertThat(first.getTxType()).isIn("입금", "출금");
			assertThat(first.getSummary()).isNotBlank();
			assertThat(Long.parseLong(first.getAmount())).isPositive();
		}
	}

	@Test
	@DisplayName("건수가 요청마다 다르면 응답 전체 길이도 다르다(진짜 가변)")
	void differentCountsDifferentLength() {
		try (TransactionHistoryClient client = new TransactionHistoryClient("127.0.0.1", server.port(), 2000, 3000)) {
			HistoryResult three = client.query("11112222333344", 3);
			HistoryResult ten = client.query("11112222333344", 10);

			assertThat(three.records()).hasSize(3);
			assertThat(ten.records()).hasSize(10);
			assertThat(ten.responseWire().length).isGreaterThan(three.responseWire().length);
		}
	}

	@Test
	@DisplayName("같은 계좌·같은 건수는 항상 같은 내역(결정론적)")
	void deterministic() {
		try (TransactionHistoryClient client = new TransactionHistoryClient("127.0.0.1", server.port(), 2000, 3000)) {
			HistoryResult a = client.query("55556666777788", 6);
			HistoryResult b = client.query("55556666777788", 6);
			assertThat(b.records().get(0).getAmount()).isEqualTo(a.records().get(0).getAmount());
			assertThat(b.records().get(5).getBalanceAfter()).isEqualTo(a.records().get(5).getBalanceAfter());
		}
	}

	@Test
	@DisplayName("풀 재사용: 한 클라이언트로 세 번 조회하면 두 번째부터 같은 소켓을 재사용(reuseCount 증가)")
	void poolReusesSocket() {
		try (TransactionHistoryClient client = new TransactionHistoryClient("127.0.0.1", server.port(), 2000, 3000)) {
			HistoryResult r1 = client.query("12345678901234", 4);
			HistoryResult r2 = client.query("12345678901234", 4);
			HistoryResult r3 = client.query("12345678901234", 4);

			assertThat(r1.reuseCount()).isZero();     // 첫 왕복은 갓 연 소켓
			assertThat(r2.reuseCount()).isEqualTo(1); // 이후는 재사용
			assertThat(r3.reuseCount()).isEqualTo(2);

			ConnectionPool.Stats stats = client.poolStats();
			assertThat(stats.created()).isEqualTo(1);  // 소켓은 하나만 열렸다
			assertThat(stats.reused()).isEqualTo(2);
			assertThat(stats.active()).isZero();       // 다 반납됨
			assertThat(stats.idle()).isEqualTo(1);
			assertThat(server.handledCount()).isEqualTo(3); // 서버는 한 연결로 3건 처리(keep-alive)
		}
	}

	@Test
	@DisplayName("서버측 partial read: 요청 전선(길이 헤더+본문)을 세 조각으로 쪼개 보내도 정상 재조립")
	void serverReassemblesSplitRequest() throws IOException, InterruptedException {
		// 요청 본문 40byte → 전선 = "0040" + 40byte = 44byte. 헤더 중간·본문 중간에서 쪼갠다.
		byte[] requestWire = LengthPrefixedFramer.encode(buildRequestBody("11112222333344", 5));
		assertThat(requestWire).hasSize(44);

		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", server.port()), 2000);
			socket.setSoTimeout(3000);
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();

			// 길이 헤더를 반으로(2byte), 본문도 나눠서 시간차를 두고 보낸다.
			out.write(requestWire, 0, 2);
			out.flush();
			Thread.sleep(60);
			out.write(requestWire, 2, 10); // 헤더 나머지 2byte + 본문 앞 8byte
			out.flush();
			Thread.sleep(60);
			out.write(requestWire, 12, requestWire.length - 12);
			out.flush();

			// 응답 전선을 직접 프레이밍해 읽는다: "0305" + 305byte.
			byte[] header = readExactly(in, 4);
			int bodyLen = Integer.parseInt(new String(header, StandardCharsets.US_ASCII));
			assertThat(bodyLen).isEqualTo(305);
			byte[] body = readExactly(in, bodyLen);
			assertThat(body).hasSize(305);
		}
	}

	@Test
	@DisplayName("동시 8건: 여러 연결이 붙어도 각자 자기 계좌의 가변 응답을 받는다")
	void concurrentClients() throws Exception {
		int n = 8;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try (TransactionHistoryClient client = new TransactionHistoryClient("127.0.0.1", server.port(), 2000, 3000)) {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				String acct = String.format("%014d", 100 + i);
				int count = 1 + (i % 7);
				results.add(pool.submit(() -> {
					HistoryResult r = client.query(acct, count);
					return r.records().size() == count
							&& r.header().getAccountNo().equals(String.valueOf(Long.parseLong(acct)));
				}));
			}
			for (Future<Boolean> f : results) {
				assertThat(f.get(5, TimeUnit.SECONDS)).isTrue();
			}
			// 동시 8건이라 소켓이 여러 개 열렸겠지만, 풀 최대(기본 4) 이하로만 만들어져야 한다.
			assertThat(client.poolStats().created()).isLessThanOrEqualTo(4);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("fault 계좌(레코드 영역 비정합): 쓰레기 응답이 RuntimeException 관통 대신 클라이언트 예외로 감싸인다 (Phase 7)")
	void faultTruncatedResponseIsWrappedInClientException() {
		try (TransactionHistoryClient client = new TransactionHistoryClient("127.0.0.1", server.port(), 2000, 3000)) {
			assertThatThrownBy(() -> client.query(MockTransactionHistoryServer.FAULT_TRUNCATED_ACCOUNT, 3))
					.isInstanceOf(TransactionHistoryClient.HistoryClientException.class)
					.hasMessageContaining("왕복은 성공")
					.hasCauseInstanceOf(io.gwanmun.message.GwanmunParseException.class);
		}
	}

	@Test
	@DisplayName("fault 계좌(쓰레기 건수 필드): 자기설명 검증의 NumberFormatException 도 클라이언트 예외로 감싸인다 (Phase 7)")
	void faultGarbageCountIsWrappedInClientException() {
		try (TransactionHistoryClient client = new TransactionHistoryClient("127.0.0.1", server.port(), 2000, 3000)) {
			assertThatThrownBy(() -> client.query(MockTransactionHistoryServer.FAULT_GARBAGE_COUNT_ACCOUNT, 3))
					.isInstanceOf(TransactionHistoryClient.HistoryClientException.class)
					.hasCauseInstanceOf(NumberFormatException.class);
		}
	}

	@Test
	@DisplayName("유휴 TTL(Phase 7): 계정계 재기동 후 TTL이 지난 낡은 소켓은 재사용되지 않고 새 소켓으로 성공한다")
	void staleConnectionAfterServerRestartIsNotReused() throws Exception {
		int port = server.port();
		// TTL 300ms짜리 풀 — 계정계가 재기동하며 닫은 소켓이 로컬에선 멀쩡해 보이는 상황을 재현한다.
		try (TransactionHistoryClient client = new TransactionHistoryClient("127.0.0.1", port, 2000, 3000, 300)) {
			HistoryResult before = client.query("12345678901234", 2);
			assertThat(before.reuseCount()).isZero();

			// 계정계 재기동 — 서버측이 소켓을 닫지만, 클라이언트 풀의 유휴 소켓은 로컬 플래그로는 유효하다.
			server.close();
			server = restartOnSamePort(port);
			Thread.sleep(350); // TTL 경과 — 낡은 소켓이 수명으로 걸러질 조건

			HistoryResult after = client.query("12345678901234", 2);
			assertThat(after.records()).hasSize(2);
			assertThat(after.reuseCount()).isZero(); // 낡은 소켓 재사용이 아니라 갓 연 소켓
			assertThat(client.poolStats().expired()).isEqualTo(1);
			assertThat(client.poolStats().created()).isEqualTo(2);
		}
	}

	/** 같은 포트로 재기동한다. 직전 소켓의 TIME_WAIT 잔재로 bind가 순간 실패할 수 있어 잠깐 재시도. */
	private static MockTransactionHistoryServer restartOnSamePort(int port) throws Exception {
		IOException last = null;
		for (int i = 0; i < 20; i++) {
			MockTransactionHistoryServer restarted = new MockTransactionHistoryServer(port);
			try {
				restarted.start();
				return restarted;
			} catch (IOException e) {
				last = e;
				Thread.sleep(100);
			}
		}
		throw last;
	}

	private static byte[] buildRequestBody(String accountNo, int count) {
		io.gwanmun.message.MessageCodec codec = new io.gwanmun.message.MessageCodec();
		return codec.build(new io.gwanmun.message.dto.TransactionHistoryRequest(
				"0300", accountNo, "20260601", "20260630", Integer.toString(count), ""));
	}

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
