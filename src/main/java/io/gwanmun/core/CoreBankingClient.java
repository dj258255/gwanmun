package io.gwanmun.core;

import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.gwanmun.message.spec.MessageSpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 목업 계정계로 나가는 TCP 클라이언트. 요청 전문을 보내고 응답 전문을 받아 온다.
 *
 * <p>받는 쪽도 프레이밍이 필요하다. 응답이 고정 61byte라도 소켓 read가 쪼개 줄 수 있어,
 * {@link FramedConnection}으로 "완성된 응답 프레임 한 개"가 될 때까지 누적해 읽는다.
 *
 * <p>연결은 요청당 하나 열고 닫는다(단순함 우선). 커넥션 풀·재사용은 부하가 필요해질 때의
 * 확장 지점이라 학습판에선 두지 않는다. 다만 무한 대기를 막는 연결·읽기 타임아웃은 건다.
 */
@Component
public class CoreBankingClient {

	private static final int RESPONSE_LENGTH = MessageSpec.of(BalanceInquiryResponse.class).totalLength();  // 61

	private final String host;
	private final int port;
	private final int connectTimeoutMs;
	private final int readTimeoutMs;

	public CoreBankingClient(
			@Value("${gwanmun.core.host:127.0.0.1}") String host,
			@Value("${gwanmun.core.port:9099}") int port,
			@Value("${gwanmun.core.connect-timeout-ms:2000}") int connectTimeoutMs,
			@Value("${gwanmun.core.read-timeout-ms:3000}") int readTimeoutMs) {
		this.host = host;
		this.port = port;
		this.connectTimeoutMs = connectTimeoutMs;
		this.readTimeoutMs = readTimeoutMs;
	}

	/**
	 * 요청 전문 프레임을 계정계로 보내고 응답 전문 프레임을 받아 반환한다. 실제 TCP 왕복.
	 *
	 * @throws IOException 연결 실패·타임아웃·응답 없이 끊김 등
	 */
	public byte[] exchange(byte[] requestFrame) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
			socket.setSoTimeout(readTimeoutMs);
			try (FramedConnection conn = new FramedConnection(socket, RESPONSE_LENGTH)) {
				conn.writeFrame(requestFrame);
				byte[] response = conn.readFrame();
				if (response == null) {
					throw new EOFException("계정계가 응답 전문 없이 연결을 닫았습니다.");
				}
				return response;
			}
		}
	}

	public String host() {
		return host;
	}

	public int port() {
		return port;
	}
}
