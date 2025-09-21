package io.gwanmun.core;

import io.gwanmun.message.AccountMasker;
import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.dto.BalanceInquiryRequest;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.gwanmun.message.dto.NetCancelRequest;
import io.gwanmun.message.dto.NetCancelResponse;
import io.gwanmun.message.dto.TransactionStatusInquiryRequest;
import io.gwanmun.message.dto.TransactionStatusInquiryResponse;
import io.gwanmun.message.spec.MessageSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 목업 계정계(코어뱅킹) TCP 서버 — 진짜 은행 없이 e2e를 재현한다.
 *
 * <p>고정길이 <b>요청 전문(52byte)</b>을 받으면 전문구분(선두 4byte)으로 갈라 처리한다 —
 * 잔액조회(0200)·거래상태조회(0400)·망취소(0420). 응답은 모두 고정 61byte. 프레이밍은
 * {@link FramedConnection}이 담당하므로 요청이 반쪽으로 쪼개져 오든(partial read) 두 건이 붙어
 * 오든(뭉침) 여기서는 "완성된 전문 한 개"만 본다.
 *
 * <p><b>Phase 6 — 계정계가 자기가 처리한 거래를 기억한다.</b> 잔액조회를 정상 처리할 때마다
 * 거래고유번호를 열쇠로 인메모리 원장에 적는다. 거래상태조회는 이 원장을 뒤져 "처리됨(01)/미처리(02)"를
 * 답하고, 망취소는 원거래를 취소 상태로 돌린다 — 게이트웨이가 UNKNOWN을 해소하는 근거가 바로
 * 이 계정계측 기록이다.
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

	private static final int REQUEST_LENGTH = MessageSpec.of(BalanceInquiryRequest.class).totalLength();  // 52

	// 요청 전문구분(선두 4byte) — 여기서 처리 경로가 갈린다.
	private static final String MSG_BALANCE_REQUEST = "0200";
	private static final String MSG_STATUS_REQUEST = "0400";
	private static final String MSG_CANCEL_REQUEST = "0420";

	private static final String RESPONSE_MESSAGE_TYPE = "0210";
	private static final String STATUS_RESPONSE_TYPE = "0410";
	private static final String CANCEL_RESPONSE_TYPE = "0430";

	private static final String OK_CODE = "0000";
	private static final String OK_MESSAGE = "정상 처리되었습니다";
	private static final String NOT_FOUND_CODE = "0001";
	// 응답메시지 필드는 20byte(한글 10자). EUC-KR로 20byte를 넘지 않는 문구여야 한다.
	private static final String NOT_FOUND_MESSAGE = "없는 계좌입니다";

	/**
	 * 응답 지연 모드 계좌(Phase 5). 이 계좌로 요청이 오면 <b>정상 처리·기록</b>하되 응답만
	 * {@code delayMillis}만큼 늦게 보내 게이트웨이 쪽 read 타임아웃을 유발한다 — "요청은 계정계에
	 * 도달해 처리됐지만 게이트웨이는 응답을 못 받은" 상황. 원장에는 UNKNOWN이 적히고, 상태조회는
	 * "처리됨"을 답하므로 <b>망취소로 해소되는 경로</b>다.
	 */
	public static final String DELAY_ACCOUNT = "99999999999999";

	/**
	 * 수신 유실 모드 계좌(Phase 6). 이 계좌로 요청이 오면 <b>기록도 응답도 없이 연결을 닫는다</b> —
	 * 요청은 나갔지만 계정계가 처리 직전에 죽은 상황. 게이트웨이는 EOF로 UNKNOWN을 적고, 상태조회는
	 * "미처리"를 답하므로 <b>FAILED로 확정되는 경로</b>다.
	 */
	public static final String DROP_ACCOUNT = "88888888888888";

	private static final long DEFAULT_DELAY_MILLIS = 5000;

	private final int requestedPort;
	private final long delayMillis;
	private final MessageCodec codec = new MessageCodec();
	private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "core-worker");
		t.setDaemon(true);
		return t;
	});
	private final AtomicInteger handled = new AtomicInteger();

	/**
	 * 계정계측 인메모리 원장 — 처리한 거래를 거래고유번호로 기억한다(Phase 6).
	 * 거래상태조회·망취소가 이 기록을 근거로 답한다.
	 */
	private final Map<String, ProcessedTransaction> processed = new ConcurrentHashMap<>();

	/** 계정계가 처리한 거래 한 건(취소되면 canceled=true로 바뀐 사본이 들어간다). */
	public record ProcessedTransaction(String accountNo, long balance, Instant processedAt, boolean canceled) {
		ProcessedTransaction canceledCopy() {
			return new ProcessedTransaction(accountNo, balance, processedAt, true);
		}
	}

	private volatile boolean running;
	private ServerSocket serverSocket;
	private Thread acceptThread;

	public MockCoreBankingServer(int port) {
		this(port, DEFAULT_DELAY_MILLIS);
	}

	/** 테스트용: 지연 모드 대기 시간을 짧게 조절할 수 있다. */
	public MockCoreBankingServer(int port, long delayMillis) {
		this.requestedPort = port;
		this.delayMillis = delayMillis;
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

	/** 테스트용: 계정계가 이 거래고유번호를 처리한 기록이 있는가. */
	public boolean hasProcessed(String tranId) {
		return processed.containsKey(tranId);
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
	 * 처리기가 {@code null}을 돌려주면(수신 유실 모드) 응답 없이 연결을 닫는다.
	 */
	private void handle(Socket socket) {
		try (FramedConnection conn = new FramedConnection(socket, REQUEST_LENGTH)) {
			byte[] requestFrame;
			while ((requestFrame = conn.readFrame()) != null) {
				byte[] responseFrame = dispatch(requestFrame);
				if (responseFrame == null) {
					// 수신 유실 모드 — 처리 직전에 죽은 계정계처럼 응답 없이 끊는다.
					return;
				}
				conn.writeFrame(responseFrame);
				handled.incrementAndGet();
			}
		} catch (IOException e) {
			// 반쪽에서 끊김·타임아웃 등. 계정계 입장에선 흔한 일이라 조용히 로깅만.
			log.debug("연결 처리 종료: {}", e.getMessage());
		}
	}

	/** 전문구분(선두 4byte)으로 처리 경로를 가른다 — 잔액조회 / 거래상태조회 / 망취소. */
	private byte[] dispatch(byte[] requestFrame) {
		String messageType = new String(requestFrame, 0, 4, StandardCharsets.US_ASCII);
		return switch (messageType) {
			case MSG_BALANCE_REQUEST -> processBalance(requestFrame);
			case MSG_STATUS_REQUEST -> processStatusInquiry(requestFrame);
			case MSG_CANCEL_REQUEST -> processNetCancel(requestFrame);
			default -> {
				log.warn("알 수 없는 전문구분 '{}' — 연결을 닫습니다", messageType);
				yield null;
			}
		};
	}

	/** 잔액조회 처리 — 여기가 계정계의 "업무 로직"(가짜 잔액 생성 + 처리 기록) 자리. */
	private byte[] processBalance(byte[] requestFrame) {
		BalanceInquiryRequest req = codec.parse(requestFrame, BalanceInquiryRequest.class);
		String accountNo = req.getAccountNo();
		// 내장 모드에서는 앱 로그에 섞이므로 여기서도 계좌를 마스킹한다.
		log.info("요청 수신: 계좌={} 거래={} 거래ID={} ({}byte)",
				AccountMasker.mask(accountNo), req.getTxCode(), req.getTranId(), requestFrame.length);

		if (DROP_ACCOUNT.equals(accountNo)) {
			// 수신 유실 모드: 읽긴 했지만 처리(기록) 없이 죽는다 — 상태조회가 "미처리"를 답하게 된다.
			log.info("수신 유실 모드 계좌 — 기록·응답 없이 연결을 닫습니다(계정계 미처리 상황 재현)");
			return null;
		}

		long value;
		try {
			value = Long.parseLong(accountNo);
		} catch (NumberFormatException e) {
			value = -1;
		}
		if (value <= 0) {
			// 없는 계좌(0 또는 파싱 불가) — 정직하게 오류 응답도 하나 둔다.
			return codec.build(new BalanceInquiryResponse(
					RESPONSE_MESSAGE_TYPE, accountNo, req.getTxCode(),
					"0", NOT_FOUND_CODE, NOT_FOUND_MESSAGE));
		}

		long balance = fakeBalance(accountNo);

		// 처리 기록 — 응답을 보내기 "전에" 적는다. 지연 모드에서 게이트웨이가 타임아웃으로 포기해도
		// 계정계 입장에서 이 거래는 처리된 것이고, 상태조회가 그 사실을 답할 수 있어야 한다.
		String tranId = req.getTranId();
		if (tranId != null && !tranId.isBlank()) {
			processed.put(tranId, new ProcessedTransaction(accountNo, balance, Instant.now(), false));
		}

		if (DELAY_ACCOUNT.equals(accountNo)) {
			// 지연 모드: 요청은 정상 수신·처리하되 응답만 늦게 보낸다(게이트웨이 타임아웃 → UNKNOWN 유발).
			log.info("지연 모드 계좌 — 처리는 완료, 응답을 {}ms 늦춥니다(게이트웨이 타임아웃 유발용)", delayMillis);
			try {
				Thread.sleep(delayMillis);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		log.info("응답 생성: 계좌={} 잔액={}원", AccountMasker.mask(accountNo), balance);
		return codec.build(new BalanceInquiryResponse(
				RESPONSE_MESSAGE_TYPE, accountNo, req.getTxCode(),
				Long.toString(balance), OK_CODE, OK_MESSAGE));
	}

	/** 거래상태조회 처리 — 인메모리 원장에서 원거래를 찾아 "처리됨(01)/미처리(02)"를 답한다. */
	private byte[] processStatusInquiry(byte[] requestFrame) {
		TransactionStatusInquiryRequest req = codec.parse(requestFrame, TransactionStatusInquiryRequest.class);
		String origTranId = req.getOrigTranId();
		ProcessedTransaction tx = processed.get(origTranId);

		String flag = tx != null
				? TransactionStatusInquiryResponse.PROCESSED
				: TransactionStatusInquiryResponse.NOT_PROCESSED;
		String message = tx != null ? "처리된 거래입니다" : "미처리 거래입니다";
		log.info("거래상태조회: 원거래={} → {} ({})", origTranId,
				tx != null ? "처리됨" : "미처리", flag);

		return codec.build(new TransactionStatusInquiryResponse(
				STATUS_RESPONSE_TYPE, origTranId, req.getTxCode(), flag, OK_CODE, message, ""));
	}

	/** 망취소 처리 — 원거래가 있으면 취소 상태로 돌린다(이미 취소된 거래도 성공 — 멱등). */
	private byte[] processNetCancel(byte[] requestFrame) {
		NetCancelRequest req = codec.parse(requestFrame, NetCancelRequest.class);
		String origTranId = req.getOrigTranId();

		ProcessedTransaction after = processed.computeIfPresent(origTranId,
				(id, tx) -> tx.canceledCopy());
		boolean canceled = after != null;
		String result = canceled ? NetCancelResponse.CANCELED : NetCancelResponse.ORIGINAL_NOT_FOUND;
		String message = canceled ? "취소 완료되었습니다" : "원거래가 없습니다";
		log.info("망취소: 원거래={} → {} ({})", origTranId, canceled ? "취소 성공" : "원거래 없음", result);

		return codec.build(new NetCancelResponse(
				CANCEL_RESPONSE_TYPE, origTranId, req.getTxCode(), result, OK_CODE, message, ""));
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
