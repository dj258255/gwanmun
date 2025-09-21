package io.gwanmun.core;

import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.gwanmun.message.spec.MessageSpec;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 목업 계정계로 나가는 TCP 클라이언트. 요청 전문을 보내고 응답 전문을 받아 온다.
 *
 * <p><b>Phase 4에서 커넥션 풀을 붙였다.</b> {@link ConnectionPool}에서 연결을 빌려 쓰고 반납해,
 * 이미 연 소켓을 재사용한다(계정계는 keep-alive라 한 소켓으로 여러 전문 왕복이 가능).
 *
 * <p><b>Phase 6에서 장애 내성 세 겹을 둘렀다.</b> 모든 왕복이 {@link ResilientExecutor}를 지난다 —
 * ①거래 단위 데드라인(재시도 포함 전체 상한, 매 시도의 read 타임아웃을 남은 시간으로 깎음)
 * ②조회성({@link TransactionKind#INQUIRY})만 지수 백오프 재시도(변경성은 재시도 금지 — 이중 거래 위험)
 * ③서킷브레이커(연속 실패 임계에서 OPEN — 죽은 계정계에 매달리지 않고 즉시 실패).
 *
 * <p>받는 쪽도 프레이밍이 필요하다. 응답이 고정 61byte라도 소켓 read가 쪼개 줄 수 있어,
 * {@link FramedConnection}으로 "완성된 응답 프레임 한 개"가 될 때까지 누적해 읽는다.
 */
@Component
public class CoreBankingClient implements Closeable {

	private static final int RESPONSE_LENGTH = MessageSpec.of(BalanceInquiryResponse.class).totalLength();  // 61

	/** 테스트용 기본 풀 설정(스프링 밖에서 4인자 생성자를 쓸 때). */
	private static final int DEFAULT_POOL_MAX = 4;
	private static final long DEFAULT_BORROW_TIMEOUT_MS = 2000;

	private final String host;
	private final int port;
	private final ConnectionPool<FramedConnection> pool;
	private final CircuitBreaker circuit;
	private final ResilientExecutor resilient;

	@Autowired
	public CoreBankingClient(
			@Value("${gwanmun.core.host:127.0.0.1}") String host,
			@Value("${gwanmun.core.port:9099}") int port,
			@Value("${gwanmun.core.connect-timeout-ms:2000}") int connectTimeoutMs,
			@Value("${gwanmun.core.read-timeout-ms:3000}") int readTimeoutMs,
			@Value("${gwanmun.core.pool.max-size:4}") int poolMaxSize,
			@Value("${gwanmun.core.pool.borrow-timeout-ms:2000}") long borrowTimeoutMs,
			ResilienceSettings resilience) {
		this.host = host;
		this.port = port;
		this.pool = new ConnectionPool<>("core-banking", poolMaxSize, borrowTimeoutMs,
				() -> {
					Socket socket = new Socket();
					socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
					socket.setSoTimeout(readTimeoutMs);
					return new FramedConnection(socket, RESPONSE_LENGTH);
				});
		this.circuit = resilience.newCircuit("core-banking");
		this.resilient = resilience.newExecutor("core-banking", circuit, readTimeoutMs);
	}

	/** 스프링 밖(테스트)에서 기본 풀·무재시도 설정으로 쓰는 생성자(Phase 5까지의 단발 호출 동작). */
	public CoreBankingClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
		this(host, port, connectTimeoutMs, readTimeoutMs, DEFAULT_POOL_MAX, DEFAULT_BORROW_TIMEOUT_MS,
				ResilienceSettings.none(readTimeoutMs));
	}

	/** 스프링 밖(테스트)에서 장애 내성 설정까지 지정하는 생성자. */
	public CoreBankingClient(String host, int port, int connectTimeoutMs, int readTimeoutMs,
			ResilienceSettings resilience) {
		this(host, port, connectTimeoutMs, readTimeoutMs, DEFAULT_POOL_MAX, DEFAULT_BORROW_TIMEOUT_MS,
				resilience);
	}

	/**
	 * 조회성 거래(잔액조회 등)의 전문 왕복. 데드라인 안에서 제한적으로 재시도한다.
	 *
	 * @throws CircuitOpenException 서킷이 열려 있어 계정계 호출 없이 즉시 거절했을 때
	 * @throws IOException          연결 실패·타임아웃·응답 없는 끊김 등(마지막 시도의 실패)
	 */
	public byte[] exchange(byte[] requestFrame) throws IOException {
		return exchange(requestFrame, TransactionKind.INQUIRY);
	}

	/**
	 * 전문 왕복 — 거래 성격을 명시하는 판. <b>변경성({@link TransactionKind#MUTATION})은 절대
	 * 재시도되지 않는다</b>(응답을 못 받은 변경성 거래의 재전송 = 이중 거래).
	 */
	public byte[] exchange(byte[] requestFrame, TransactionKind kind) throws IOException {
		return resilient.execute(kind, readTimeoutMs -> exchangeOnce(requestFrame, readTimeoutMs));
	}

	/**
	 * 한 번의 실제 TCP 왕복. 소켓은 풀에서 빌려 재사용하고, 이번 시도의 read 타임아웃은
	 * 거래 데드라인의 남은 시간으로 깎인 값을 쓴다.
	 */
	private byte[] exchangeOnce(byte[] requestFrame, int readTimeoutMs) throws IOException {
		try (ConnectionPool<FramedConnection>.Lease lease = pool.borrow()) {
			try {
				FramedConnection conn = lease.connection();
				conn.setReadTimeout(readTimeoutMs);
				conn.writeFrame(requestFrame);
				byte[] response = conn.readFrame();
				if (response == null) {
					lease.invalidate();
					throw new EOFException("계정계가 응답 전문 없이 연결을 닫았습니다.");
				}
				return response;
			} catch (IOException | RuntimeException e) {
				// 소켓·프레이밍이 깨졌으면 이 연결은 재사용하지 않고 폐기한다.
				lease.invalidate();
				throw e;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("계정계 연결 대기 중 인터럽트", e);
		}
	}

	/** 현재 풀 상태(활성/유휴/재사용 횟수 등) 스냅샷. */
	public ConnectionPool.Stats poolStats() {
		return pool.stats();
	}

	/** 현재 서킷 상태(CLOSED/OPEN/HALF_OPEN·연속 실패·거절 수) 스냅샷. */
	public CircuitBreaker.Stats circuitStats() {
		return circuit.stats();
	}

	/** 누적 재시도 횟수(메트릭용). */
	public long retriesTotal() {
		return resilient.retriesTotal();
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
}
