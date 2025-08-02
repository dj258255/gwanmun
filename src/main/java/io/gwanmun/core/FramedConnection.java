package io.gwanmun.core;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * 소켓 하나를 감싸, 바이트 스트림을 "완성된 전문 프레임" 단위로 읽고 쓴다.
 *
 * <p>{@link #readFrame()}이 프레이밍의 실체다. 소켓 {@code read()}는 요청한 만큼
 * 다 준다는 보장이 없어서(한 번에 몇 바이트가 올지 모른다), 프레임 하나가 완성될 때까지
 * 여러 번 read하며 {@link FixedLengthFramer}에 누적한다. 반대로 한 번의 read에 두 전문이
 * 붙어 와도(뭉침) 누적기가 프레임 경계에서 정확히 잘라 준다.
 *
 * <p>read 버퍼를 일부러 작게(64byte) 잡았다. "고정 61byte 전문"조차 한 번의 read로 다
 * 안 온다는 것을 코드가 구조적으로 다루게 하기 위함이다(운에 기대지 않는다).
 */
public final class FramedConnection implements Closeable {

	/** 한 번의 소켓 read로 받는 조각 크기. 전문 길이보다 작게 두어 partial read를 정상 경로로 만든다. */
	private static final int CHUNK = 64;

	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final FixedLengthFramer framer;
	private final byte[] chunk = new byte[CHUNK];

	public FramedConnection(Socket socket, int frameLength) throws IOException {
		this.socket = socket;
		this.in = socket.getInputStream();
		this.out = socket.getOutputStream();
		this.framer = new FixedLengthFramer(frameLength);
	}

	/**
	 * 완성된 전문 하나를 읽어 반환한다. 프레임이 다 모일 때까지 소켓에서 반복 read한다.
	 *
	 * @return 완성된 프레임 바이트. 프레임 경계에서 상대가 정상 종료하면(EOF, 반쪽 없음) {@code null}.
	 * @throws EOFException 전문 중간에 연결이 끊겨 프레임을 완성하지 못했을 때(반쪽만 도착)
	 * @throws IOException  소켓 read 실패(타임아웃 포함)
	 */
	public byte[] readFrame() throws IOException {
		// 지난 read에서 뭉쳐 들어온 다음 전문이 이미 버퍼에 있을 수 있다.
		byte[] frame = framer.next();
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
			// 아직 프레임이 덜 찼다. 다음 조각을 기다린다(partial read를 계속 누적).
		}
	}

	/** 전문 프레임 하나를 그대로 소켓에 쓴다. */
	public void writeFrame(byte[] frame) throws IOException {
		out.write(frame);
		out.flush();
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}
}
