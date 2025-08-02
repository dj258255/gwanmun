package io.gwanmun.core;

import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.dto.BalanceInquiryRequest;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.gwanmun.message.spec.MessageSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 목업 계정계(코어뱅킹) TCP 서버 — 진짜 은행 없이 e2e를 재현한다.
 *
 * <p>고정길이 잔액조회 <b>요청 전문(30byte)</b>을 받으면, 계좌번호로 그럴듯한 가짜 잔액을 만들어
 * 잔액조회 <b>응답 전문(61byte)</b>으로 돌려준다. 프레이밍은 {@link FramedConnection}이 담당하므로
 * 요청이 반쪽으로 쪼개져 오든(partial read) 두 건이 붙어 오든(뭉침) 여기서는 "완성된 전문 한 개"만 본다.
 *
 * <p>비즈니스 로직(잔액)은 계정계의 몫이라 여기에 둔다 — 관문(gwanmun)은 흐름만 통제하고 계산은
 * 위임한다는 로드맵 원칙. 잔액은 계좌번호에서 <b>결정론적</b>으로 만든다(같은 계좌 → 같은 잔액).
 * 실제 은행 로직이 아니라 시연용 합성값이다.
 *
 * <p>Spring 앱 안에서 별도 포트로 띄우거나({@link MockCoreBankingLifecycle}),
 * {@link #main(String[])}으로 독립 프로세스로 실행할 수 있다.
 */
public final class MockCoreBankingServer implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(MockCoreBankingServer.class);

	private static final int REQUEST_LENGTH = MessageSpec.of(BalanceInquiryRequest.class).totalLength();  // 30
	private static final String RESPONSE_MESSAGE_TYPE = "0210";
	private static final String OK_CODE = "0000";
	private static final String OK_MESSAGE = "정상 처리되었습니다";
	private static final String NOT_FOUND_CODE = "0001";
	// 응답메시지 필드는 20byte(한글 10자). EUC-KR로 20byte를 넘지 않는 문구여야 한다.
	private static final String NOT_FOUND_MESSAGE = "없는 계좌입니다";

	private final int requestedPort;
	private final MessageCodec codec = new MessageCodec();
	private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "core-worker");
		t.setDaemon(true);
		return t;
	});
	private final AtomicInteger handled = new AtomicInteger();

	private volatile boolean running;
	private ServerSocket serverSocket;
	private Thread acceptThread;

	public MockCoreBankingServer(int port) {
		this.requestedPort = port;
	}

	/** 서버 소켓을 열고 accept 루프를 시작한다. port=0 이면 임의의 빈 포트에 바인딩된다. */
	public synchronized void start() throws IOException {
		if (running) {
			return;
		}
		serverSocket = new ServerSocket(requestedPort);
		running = true;
		acceptThread = new Thread(this::acceptLoop, "core-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();
		log.info("목업 계정계 TCP 서버 기동: 포트={} (요청 전문 {}byte 대기)", port(), REQUEST_LENGTH);
	}

	/** 실제 바인딩된 포트(임의 포트로 띄웠을 때 확인용). */
	public int port() {
		return serverSocket.getLocalPort();
	}

	public int handledCount() {
		return handled.get();
	}

	private void acceptLoop() {
		while (running) {
			try {
				Socket socket = serverSocket.accept();
				workers.submit(() -> handle(socket));
			} catch (IOException e) {
				if (running) {
					log.warn("accept 실패", e);
				}
				// close()로 소켓이 닫혀 accept가 깨지면 running=false 이므로 루프 종료.
			}
		}
	}

	/**
	 * 한 연결을 처리한다. 같은 연결에서 여러 요청이 연속으로 올 수 있으므로(keep-alive),
	 * 프레임이 더 안 올 때까지 반복해서 요청 전문을 읽고 응답 전문을 돌려준다.
	 */
	private void handle(Socket socket) {
		try (FramedConnection conn = new FramedConnection(socket, REQUEST_LENGTH)) {
			byte[] requestFrame;
			while ((requestFrame = conn.readFrame()) != null) {
				BalanceInquiryResponse response = process(requestFrame);
				conn.writeFrame(codec.build(response));
				handled.incrementAndGet();
			}
		} catch (IOException e) {
			// 반쪽에서 끊김·타임아웃 등. 계정계 입장에선 흔한 일이라 조용히 로깅만.
			log.debug("연결 처리 종료: {}", e.getMessage());
		}
	}

	/** 요청 전문 → 응답 전문 DTO. 여기가 계정계의 "업무 로직"(가짜 잔액 생성) 자리. */
	private BalanceInquiryResponse process(byte[] requestFrame) {
		BalanceInquiryRequest req = codec.parse(requestFrame, BalanceInquiryRequest.class);
		String accountNo = req.getAccountNo();
		log.info("요청 수신: 계좌={} 거래={} ({}byte)", accountNo, req.getTxCode(), requestFrame.length);

		long value;
		try {
			value = Long.parseLong(accountNo);
		} catch (NumberFormatException e) {
			value = -1;
		}
		if (value <= 0) {
			// 없는 계좌(0 또는 파싱 불가) — 정직하게 오류 응답도 하나 둔다.
			return new BalanceInquiryResponse(
					RESPONSE_MESSAGE_TYPE, accountNo, req.getTxCode(),
					"0", NOT_FOUND_CODE, NOT_FOUND_MESSAGE);
		}

		long balance = fakeBalance(accountNo);
		log.info("응답 생성: 계좌={} 잔액={}원", accountNo, balance);
		return new BalanceInquiryResponse(
				RESPONSE_MESSAGE_TYPE, accountNo, req.getTxCode(),
				Long.toString(balance), OK_CODE, OK_MESSAGE);
	}

	/**
	 * 계좌번호에서 결정론적으로 만든 가짜 잔액(원). 같은 계좌면 항상 같은 값이라 시연·테스트가 재현된다.
	 * 1,000원 단위, 대략 백만~90억 원 범위로 은행 잔액처럼 보이게만 한다(실제 계산 아님).
	 */
	private long fakeBalance(String accountNo) {
		long h = accountNo.hashCode() & 0x7fffffffL;
		return 1_000L * (1_000L + (h % 8_999_000L));
	}

	@Override
	public synchronized void close() {
		running = false;
		try {
			if (serverSocket != null) {
				serverSocket.close(); // accept()를 깨운다
			}
		} catch (IOException e) {
			log.debug("서버 소켓 종료 중 예외: {}", e.getMessage());
		}
		workers.shutdownNow();
		log.info("목업 계정계 TCP 서버 종료 (처리한 요청 {}건)", handled.get());
	}

	// ------------------------------------------------------------------
	// 독립 실행 진입점 — 앱과 별개의 프로세스로 계정계를 띄운다.
	//   java ... io.gwanmun.core.MockCoreBankingServer [port]
	// ------------------------------------------------------------------
	public static void main(String[] args) throws Exception {
		int port = args.length > 0 ? Integer.parseInt(args[0]) : 9099;
		MockCoreBankingServer server = new MockCoreBankingServer(port);
		server.start();
		Runtime.getRuntime().addShutdownHook(new Thread(server::close));
		log.info("독립 실행 모드. 종료하려면 Ctrl+C. 포트={}", server.port());
		// accept 스레드가 데몬이라, 메인이 살아 있어야 프로세스가 유지된다.
		Thread.currentThread().join();
	}
}
