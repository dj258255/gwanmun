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
 * <p><b>Phase 4에서 커넥션 풀을 붙였다.</b> Phase 2에서는 {@code exchange()}가 요청마다 소켓을 새로
 * 열고 닫아, TCP 핸드셰이크·소켓 자원을 매번 지불했다. 이제 {@link ConnectionPool}에서 연결을
 * 빌려 쓰고 반납해, 이미 연 소켓을 <b>재사용</b>한다. 계정계는 keep-alive라 한 소켓으로 여러 전문을
 * 주고받을 수 있어(서버측 {@code handle} 루프가 프레임이 더 안 올 때까지 읽는다) 풀이 성립한다.
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

	@Autowired
	public CoreBankingClient(
			@Value("${gwanmun.core.host:127.0.0.1}") String host,
			@Value("${gwanmun.core.port:9099}") int port,
			@Value("${gwanmun.core.connect-timeout-ms:2000}") int connectTimeoutMs,
			@Value("${gwanmun.core.read-timeout-ms:3000}") int readTimeoutMs,
			@Value("${gwanmun.core.pool.max-size:4}") int poolMaxSize,
			@Value("${gwanmun.core.pool.borrow-timeout-ms:2000}") long borrowTimeoutMs) {
		this.host = host;
		this.port = port;
		this.pool = new ConnectionPool<>("core-banking", poolMaxSize, borrowTimeoutMs,
				() -> {
					Socket socket = new Socket();
					socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
					socket.setSoTimeout(readTimeoutMs);
					return new FramedConnection(socket, RESPONSE_LENGTH);
				});
	}

	/** 스프링 밖(테스트)에서 기본 풀 설정으로 쓰는 생성자. */
	public CoreBankingClient(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
		this(host, port, connectTimeoutMs, readTimeoutMs, DEFAULT_POOL_MAX, DEFAULT_BORROW_TIMEOUT_MS);
	}

	/**
	 * 요청 전문 프레임을 계정계로 보내고 응답 전문 프레임을 받아 반환한다. 실제 TCP 왕복이며,
	 * 소켓은 풀에서 빌려 재사용한다.
	 *
	 * @throws IOException 연결 실패·타임아웃·응답 없이 끊김 등
	 */
	public byte[] exchange(byte[] requestFrame) throws IOException {
		try (ConnectionPool<FramedConnection>.Lease lease = pool.borrow()) {
			try {
				FramedConnection conn = lease.connection();
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
