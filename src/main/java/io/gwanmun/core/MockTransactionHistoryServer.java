package io.gwanmun.core;

import io.gwanmun.message.AccountMasker;
import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.VariableMessageCodec;
import io.gwanmun.message.dto.TransactionHistoryHeader;
import io.gwanmun.message.dto.TransactionHistoryRequest;
import io.gwanmun.message.dto.TransactionRecord;
import io.gwanmun.message.spec.MessageSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 목업 계정계(거래내역 조회) TCP 서버 — <b>가변 전문</b>과 <b>길이 프리픽스 프레이밍</b>을 재현한다.
 *
 * <p>{@link MockCoreBankingServer}(잔액조회, 고정길이)와 나란히 별도 포트로 뜬다. 거래내역 조회
 * 요청({@link TransactionHistoryRequest}, 고정 40byte)을 받으면, 계좌번호로 그럴듯한 거래 레코드
 * N건을 결정론적으로 만들어 <b>고정 헤더 + 레코드 N건</b>의 가변 응답으로 돌려준다. 프레이밍은
 * {@link LengthPrefixedConnection}이 맡으므로, 요청이 반쪽으로 쪼개져 오든 두 건이 붙어 오든
 * 여기서는 "완성된 본문 한 개"만 본다.
 *
 * <p>레코드 건수·금액·잔액·적요는 계좌번호에서 결정론적으로 합성한다(같은 계좌 → 같은 내역).
 * 실제 은행 로직이 아니라 시연용이다.
 */
public final class MockTransactionHistoryServer implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(MockTransactionHistoryServer.class);

	private static final int REQUEST_LENGTH = MessageSpec.of(TransactionHistoryRequest.class).totalLength();  // 40
	private static final int MAX_BODY_LENGTH = 9999;
	private static final int MAX_RECORDS = 20; // 한 응답에 담는 레코드 상한(4byte 헤더 안에서 안전)
	private static final String RESPONSE_MESSAGE_TYPE = "0310";
	private static final String OK_CODE = "0000";

	private static final String[] SUMMARIES = {"급여이체", "카드결제", "공과금", "이자입금", "송금", "현금인출"};
	private static final String[] TX_TYPES = {"입금", "출금"};

	/**
	 * fault 모드 계좌(Phase 7) — <b>레코드 영역 비정합</b>. 정상 응답을 만든 뒤 끝을 몇 byte 잘라 보낸다.
	 * 길이 헤더는 잘린 길이와 일치하므로 전송 계층(프레이밍)은 통과하고, 파싱 단계에서 "레코드가 딱
	 * 떨어지지 않는 쓰레기 응답"이 된다 — 계정계 버그·중간 변조로 본문이 깨진 상황의 재현.
	 */
	public static final String FAULT_TRUNCATED_ACCOUNT = "77777777777777";

	/**
	 * fault 모드 계좌(Phase 7) — <b>쓰레기 건수 필드</b>. 구조는 온전하지만 헤더의 recordCount 자리에
	 * 숫자가 아닌 바이트를 심어 보낸다. 파싱은 통과하고, 자기설명 검증의 숫자 변환에서 터진다 —
	 * "필드 값이 계약(숫자)을 어기는" 이상 응답의 재현.
	 */
	public static final String FAULT_GARBAGE_COUNT_ACCOUNT = "66666666666666";

	private final int requestedPort;
	private final MessageCodec codec = new MessageCodec();
	private final VariableMessageCodec variableCodec = new VariableMessageCodec(codec);
	private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "txn-history-worker");
		t.setDaemon(true);
		return t;
	});
	private final AtomicInteger handled = new AtomicInteger();

	private volatile boolean running;
	private ServerSocket serverSocket;
	private Thread acceptThread;

	public MockTransactionHistoryServer(int port) {
		this.requestedPort = port;
	}

	/** 서버 소켓을 열고 accept 루프를 시작한다. port=0 이면 임의의 빈 포트에 바인딩된다. */
	public synchronized void start() throws IOException {
		if (running) {
			return;
		}
		serverSocket = new ServerSocket(requestedPort);
		running = true;
		acceptThread = new Thread(this::acceptLoop, "txn-history-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();
		log.info("목업 계정계(거래내역) TCP 서버 기동: 포트={} (요청 전문 {}byte, 가변 응답)", port(), REQUEST_LENGTH);
	}

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
			}
		}
	}

	/**
	 * 한 연결을 처리한다. keep-alive라 프레임이 더 안 올 때까지 반복해서 요청 본문을 읽고 응답 본문을
	 * 돌려준다(커넥션 풀이 소켓을 재사용하는 전제).
	 */
	private void handle(Socket socket) {
		try (LengthPrefixedConnection conn = new LengthPrefixedConnection(socket, MAX_BODY_LENGTH)) {
			byte[] requestBody;
			while ((requestBody = conn.readFrame()) != null) {
				byte[] responseBody = process(requestBody);
				conn.writeFrame(responseBody);
				handled.incrementAndGet();
			}
		} catch (IOException e) {
			log.debug("연결 처리 종료: {}", e.getMessage());
		}
	}

	/** 요청 본문 → 가변 응답 본문(헤더 + 레코드 N건). */
	private byte[] process(byte[] requestBody) {
		TransactionHistoryRequest req = codec.parse(requestBody, TransactionHistoryRequest.class);
		String accountNo = req.getAccountNo();
		int requested = clampCount(req.getReqCount());
		// 내장 모드에서는 앱 로그에 섞이므로 여기서도 계좌를 마스킹한다.
		log.info("거래내역 요청 수신: 계좌={} 요청건수={} ({}byte)",
				AccountMasker.mask(accountNo), requested, requestBody.length);

		List<TransactionRecord> records = synthesize(accountNo, requested);

		int headerLen = MessageSpec.of(TransactionHistoryHeader.class).totalLength();
		int recordLen = MessageSpec.of(TransactionRecord.class).totalLength();
		int totalLength = headerLen + records.size() * recordLen;

		TransactionHistoryHeader header = new TransactionHistoryHeader(
				RESPONSE_MESSAGE_TYPE, accountNo,
				Integer.toString(records.size()), Integer.toString(totalLength), OK_CODE);

		log.info("거래내역 응답 생성: 계좌={} 건수={} 본문={}byte",
				AccountMasker.mask(accountNo), records.size(), totalLength);
		byte[] body = variableCodec.build(header, records);
		return applyFaultMode(accountNo, body);
	}

	/** fault 모드 계좌면 정상 본문을 "쓰레기 응답"으로 망가뜨린다(Phase 7 — 이상 응답 처리 검증용). */
	private byte[] applyFaultMode(String accountNo, byte[] body) {
		if (FAULT_TRUNCATED_ACCOUNT.equals(accountNo)) {
			// 끝 3byte를 잘라 레코드 영역이 레코드 길이로 나눠떨어지지 않게 한다.
			log.info("fault 모드(레코드 영역 비정합) — 본문 끝 3byte를 잘라 보냅니다");
			return java.util.Arrays.copyOfRange(body, 0, body.length - 3);
		}
		if (FAULT_GARBAGE_COUNT_ACCOUNT.equals(accountNo)) {
			// 헤더의 recordCount 필드(오프셋 18, 3byte)에 비숫자 바이트를 심는다. 코덱은 이런 전문을
			// 만들 수 없으므로(빌드 검증) 바이트를 직접 오염시킨다 — 진짜 "계정계가 보낸 쓰레기"다.
			log.info("fault 모드(쓰레기 건수 필드) — recordCount 를 'X?A' 로 오염시켜 보냅니다");
			byte[] corrupted = body.clone();
			corrupted[18] = 'X';
			corrupted[19] = '?';
			corrupted[20] = 'A';
			return corrupted;
		}
		return body;
	}

	private int clampCount(String reqCount) {
		int n;
		try {
			n = Integer.parseInt(reqCount.trim());
		} catch (NumberFormatException e) {
			n = 1;
		}
		return Math.max(1, Math.min(MAX_RECORDS, n));
	}

	/** 계좌번호에서 결정론적으로 만든 거래 레코드 N건(같은 계좌·같은 건수 → 같은 내역). */
	private List<TransactionRecord> synthesize(String accountNo, int count) {
		long seed = accountNo.hashCode() & 0x7fffffffL;
		long balance = 1_000L * (1_000L + (seed % 8_999_000L)); // 시작 잔액(잔액조회 서버와 같은 결)

		List<TransactionRecord> records = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			long h = (seed + 31L * (i + 1)) & 0x7fffffffL;
			boolean deposit = (h % 2) == 0;
			long amount = 1_000L * (1 + (h % 500)); // 1천~50만원, 1천원 단위
			balance += deposit ? amount : -amount;
			if (balance < 0) {
				balance += amount * 2; // 시연용: 잔액이 음수로 가지 않게 보정
			}
			int day = 1 + (int) (h % 28);
			String txDate = String.format("202606%02d", day);
			String summary = SUMMARIES[(int) (h % SUMMARIES.length)];

			records.add(new TransactionRecord(
					Integer.toString(i + 1),
					txDate,
					TX_TYPES[deposit ? 0 : 1],
					Long.toString(amount),
					Long.toString(balance),
					summary));
		}
		return records;
	}

	@Override
	public synchronized void close() {
		running = false;
		try {
			if (serverSocket != null) {
				serverSocket.close();
			}
		} catch (IOException e) {
			log.debug("서버 소켓 종료 중 예외: {}", e.getMessage());
		}
		workers.shutdownNow();
		log.info("목업 계정계(거래내역) TCP 서버 종료 (처리한 요청 {}건)", handled.get());
	}
}
