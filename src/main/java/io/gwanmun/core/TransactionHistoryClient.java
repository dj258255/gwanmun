package io.gwanmun.core;

import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.VariableMessageCodec;
import io.gwanmun.message.VariableMessageCodec.VariableMessage;
import io.gwanmun.message.dto.TransactionHistoryHeader;
import io.gwanmun.message.dto.TransactionHistoryRequest;
import io.gwanmun.message.dto.TransactionRecord;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * 거래내역 조회(가변 전문) 클라이언트. 요청 전문을 <b>길이 헤더 + 본문</b>으로 보내고, 레코드 N건이
 * 가변으로 붙은 응답을 받아 헤더 + 레코드로 파싱한다.
 *
 * <p>Phase 4의 세 조각이 여기서 만난다 — 가변 프레이밍({@link LengthPrefixedConnection}),
 * 가변 전문 코덱({@link VariableMessageCodec}), 커넥션 풀({@link ConnectionPool}). 소켓은 풀에서
 * 빌려 재사용한다.
 */
@Component
public class TransactionHistoryClient implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(TransactionHistoryClient.class);

	private static final String REQUEST_MESSAGE_TYPE = "0300";
	/** 4byte ASCII 길이 헤더가 표현 가능한 상한 안에서 넉넉히 잡은 본문 상한. */
	private static final int MAX_BODY_LENGTH = 9999;

	private static final int DEFAULT_POOL_MAX = 4;
	private static final long DEFAULT_BORROW_TIMEOUT_MS = 2000;

	private final String host;
	private final int port;
	private final MessageCodec codec = new MessageCodec();
	private final VariableMessageCodec variableCodec = new VariableMessageCodec();
	private final ConnectionPool<LengthPrefixedConnection> pool;

	@Autowired
	public TransactionHistoryClient(
			@Value("${gwanmun.core.host:127.0.0.1}") String host,
			@Value("${gwanmun.core.history.port:9098}") int port,
			@Value("${gwanmun.core.connect-timeout-ms:2000}") int connectTimeoutMs,
			@Value("${gwanmun.core.read-timeout-ms:3000}") int readTimeoutMs,
			@Value("${gwanmun.core.pool.max-size:4}") int poolMaxSize,
			@Value("${gwanmun.core.pool.borrow-timeout-ms:2000}") long borrowTimeoutMs) {
		this.host = host;
		this.port = port;
		this.pool = new ConnectionPool<>("txn-history", poolMaxSize, borrowTimeoutMs,
				() -> {
					Socket socket = new Socket();
					socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
					socket.setSoTimeout(readTimeoutMs);
					return new LengthPrefixedConnection(socket, MAX_BODY_LENGTH);
				});
	}

	/** 테스트용 기본 풀 설정 생성자. */
	public TransactionHistoryClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
		this(host, port, connectTimeoutMs, readTimeoutMs, DEFAULT_POOL_MAX, DEFAULT_BORROW_TIMEOUT_MS);
	}

	/**
	 * 거래내역 한 건을 조회한다. 요청/응답의 전선(wire) 바이트(길이 헤더 포함)와 파싱 결과를 함께
	 * 담아 돌려준다(hex로 길이 헤더가 보이게).
	 */
	public HistoryResult query(String accountNo, int requestedCount) {
		TransactionHistoryRequest request = new TransactionHistoryRequest(
				REQUEST_MESSAGE_TYPE, accountNo, "20260601", "20260630",
				Integer.toString(requestedCount), "");
		byte[] requestBody = codec.build(request);
		byte[] requestWire = LengthPrefixedFramer.encode(requestBody);

		long startNanos = System.nanoTime();
		byte[] responseBody;
		int reuseCount;
		try (ConnectionPool<LengthPrefixedConnection>.Lease lease = pool.borrow()) {
			reuseCount = lease.reuseCount();
			try {
				LengthPrefixedConnection conn = lease.connection();
				conn.writeFrame(requestBody);
				responseBody = conn.readFrame();
				if (responseBody == null) {
					lease.invalidate();
					throw new EOFException("계정계가 응답 전문 없이 연결을 닫았습니다.");
				}
			} catch (IOException | RuntimeException e) {
				lease.invalidate();
				throw e;
			}
		} catch (IOException e) {
			throw new HistoryClientException(
					"거래내역 계정계(" + host + ":" + port + ") 통신 실패: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new HistoryClientException("거래내역 연결 대기 중 인터럽트", e);
		}
		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

		VariableMessage<TransactionHistoryHeader, TransactionRecord> parsed =
				variableCodec.parse(responseBody, TransactionHistoryHeader.class, TransactionRecord.class);

		// 헤더가 스스로 밝힌 건수·전체길이와 실제 파싱 결과가 맞는지 교차검증(전문의 자기 설명 확인).
		int declaredCount = Integer.parseInt(parsed.header().getRecordCount());
		int declaredLength = Integer.parseInt(parsed.header().getTotalLength());
		if (declaredCount != parsed.records().size() || declaredLength != responseBody.length) {
			throw new HistoryClientException(String.format(
					"응답 헤더 자기설명 불일치: 헤더 건수=%d/실제=%d, 헤더 길이=%d/실제=%d",
					declaredCount, parsed.records().size(), declaredLength, responseBody.length));
		}

		byte[] responseWire = LengthPrefixedFramer.encode(responseBody);
		log.info("거래내역 조회 완료: 계좌={} 건수={} 본문={}byte 재사용={}회 ({}ms)",
				accountNo, parsed.records().size(), responseBody.length, reuseCount, elapsedMs);

		return new HistoryResult(requestWire, responseWire, parsed.header(), parsed.records(),
				host, port, elapsedMs, reuseCount);
	}

	/** 현재 풀 상태 스냅샷. */
	public ConnectionPool.Stats poolStats() {
		return pool.stats();
	}

	public String host() {
		return host;
	}

	public int port() {
		return port;
	}

	@PreDestroy
	@Override
	public void close() {
		pool.close();
	}

	/**
	 * 조회 결과. 소켓을 실제로 타고 오간 전선 바이트(길이 헤더 포함)를 그대로 들고 있어(hex로 보이게),
	 * "가변 전문이 소켓을 오갔음"과 "이번 왕복이 몇 번째 재사용 소켓이었는지"가 드러나게 한다.
	 */
	public record HistoryResult(
			byte[] requestWire,
			byte[] responseWire,
			TransactionHistoryHeader header,
			List<TransactionRecord> records,
			String coreHost,
			int corePort,
			long elapsedMs,
			int reuseCount
	) {
	}

	/** 거래내역 계정계 통신 실패. */
	public static class HistoryClientException extends RuntimeException {
		public HistoryClientException(String message) {
			super(message);
		}

		public HistoryClientException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
