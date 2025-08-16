package io.gwanmun.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * 소켓 하나를 감싸, 바이트 스트림을 <b>길이 헤더 + 본문</b> 단위로 읽고 쓴다.
 * {@link FramedConnection}의 가변길이 판(版)이다.
 *
 * <p>{@link #readFrame()}은 {@link LengthPrefixedFramer}에 소켓 조각을 누적하며, 헤더 4byte로
 * 본문 길이를 안 뒤 그 본문이 다 모일 때까지 여러 번 read한다(2단계). {@link #writeFrame(byte[])}는
 * 반대로 본문 앞에 4byte 길이 헤더를 붙여 내보낸다.
 *
 * <p>read 버퍼를 작게(16byte) 잡아, 헤더·본문이 한 번의 read로 다 오지 않는 상황을 코드가
 * 구조적으로 다루게 한다(운에 기대지 않는다).
 */
public final class LengthPrefixedConnection implements PoolableConnection {

	/** 한 번의 소켓 read로 받는 조각 크기. 작게 두어 헤더·본문 partial read를 정상 경로로 만든다. */
	private static final int CHUNK = 16;

	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final LengthPrefixedFramer framer;
	private final byte[] chunk = new byte[CHUNK];

	public LengthPrefixedConnection(Socket socket, int maxBodyLength) throws IOException {
		this.socket = socket;
		this.in = socket.getInputStream();
		this.out = socket.getOutputStream();
		this.framer = new LengthPrefixedFramer(maxBodyLength);
	}

	/**
	 * 완성된 전문 본문 하나를 읽어 반환한다. 헤더와 본문이 다 모일 때까지 소켓에서 반복 read한다.
	 *
	 * @return 완성된 본문 바이트. 프레임 경계에서 상대가 정상 종료하면(EOF, 반쪽 없음) {@code null}.
	 * @throws EOFException           전문 중간에 연결이 끊겼을 때(헤더/본문 반쪽만 도착)
	 * @throws MalformedFrameException 길이 헤더가 비정상일 때
	 * @throws IOException            소켓 read 실패(타임아웃 포함)
	 */
	public byte[] readFrame() throws IOException {
		byte[] frame = framer.next(); // 지난 read에서 뭉쳐 온 다음 전문이 이미 버퍼에 있을 수 있다.
		if (frame != null) {
			return frame;
		}
		while (true) {
			int n = in.read(chunk);
			if (n < 0) {
				if (framer.hasPartial()) {
					throw new EOFException("연결이 전문 중간에 끊겼습니다. 버퍼에 "
							+ framer.buffered() + " byte 남음(프레임 미완성).");
				}
				return null; // 프레임 경계에서의 깔끔한 종료
			}
			framer.feed(chunk, 0, n);
			frame = framer.next();
			if (frame != null) {
				return frame;
			}
			// 아직 헤더나 본문이 덜 찼다. 다음 조각을 기다린다.
		}
	}

	/** 본문 하나를 길이 헤더와 함께 소켓에 쓴다. */
	public void writeFrame(byte[] body) throws IOException {
		out.write(LengthPrefixedFramer.encode(body));
		out.flush();
	}

	@Override
	public boolean isValid() {
		return !socket.isClosed()
				&& socket.isConnected()
				&& !socket.isInputShutdown()
				&& !socket.isOutputShutdown();
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}
}
