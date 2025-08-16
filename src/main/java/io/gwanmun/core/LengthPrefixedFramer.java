package io.gwanmun.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 가변길이 전문의 프레이밍 누적기 — <b>길이 헤더(4byte ASCII 숫자) + 본문</b> 구조. Phase 4의 핵심.
 *
 * <p><b>왜 고정길이 프레이밍({@link FixedLengthFramer})으로 안 되나.</b> 거래내역 조회 응답처럼
 * 레코드가 몇 건 붙을지 런타임에 정해지는 전문은 전체 길이가 매번 다르다. "프레임 길이 상수"를
 * 알 수 없으니, 전문 앞머리에 <b>본문이 몇 byte인지</b>를 적은 길이 헤더를 두고 그 값을 읽어
 * 경계를 잡는다.
 *
 * <p><b>2단계 읽기.</b> TCP는 바이트 스트림이라 헤더조차 반쪽으로 올 수 있다.
 * <ol>
 *   <li><b>1단계</b>: 4byte 헤더가 다 모일 때까지 기다린다(헤더 반쪽 방어).</li>
 *   <li><b>2단계</b>: 헤더를 숫자로 읽어 본문 길이 L을 안 뒤, 본문 L byte가 다 모일 때까지 더
 *       기다린다(본문 반쪽 방어).</li>
 * </ol>
 * 헤더+본문이 다 차야 한 프레임(본문 byte[])을 잘라 내보낸다. 여러 전문이 붙어 와도(뭉침)
 * {@link #next()}를 반복 호출하면 순서대로 꺼낼 수 있다.
 *
 * <p><b>비정상 길이 방어.</b> 헤더가 숫자가 아니거나(손상·쓰레기 바이트), 설정한 상한을 넘는 길이를
 * 요구하면 {@link MalformedFrameException}으로 즉시 실패한다. 4byte ASCII 십진수는 음수를 표현할
 * 수 없지만, 그 자리에 온 비-숫자·과대 길이가 현실의 "비정상 길이"다 — 믿지 않고 끊는다.
 *
 * <p>스레드 안전하지 않다. 한 연결(소켓)당 하나씩 쓰는 것을 전제로 한다.
 */
public final class LengthPrefixedFramer {

	/** 길이 헤더 byte 수(ASCII 십진수). 4자리 → 본문 최대 9999 byte를 표현. */
	public static final int HEADER_LENGTH = 4;

	private final int maxBodyLength;
	private byte[] buffer;
	private int size; // buffer[0..size) 가 유효한 누적 바이트

	public LengthPrefixedFramer(int maxBodyLength) {
		if (maxBodyLength <= 0) {
			throw new IllegalArgumentException("최대 본문 길이는 1 이상이어야 합니다: " + maxBodyLength);
		}
		// 4byte ASCII 헤더로는 9999까지만 표현 가능하다. 상한이 그보다 크면 헤더가 못 담는다.
		if (maxBodyLength > 9999) {
			throw new IllegalArgumentException(
					"4byte ASCII 길이 헤더는 최대 9999 byte까지만 표현합니다: " + maxBodyLength);
		}
		this.maxBodyLength = maxBodyLength;
		this.buffer = new byte[Math.max(HEADER_LENGTH + maxBodyLength, 64)];
		this.size = 0;
	}

	/** 소켓 read로 들어온 바이트 조각을 누적 버퍼에 이어붙인다. */
	public void feed(byte[] data, int offset, int len) {
		if (offset < 0 || len < 0 || offset + len > data.length) {
			throw new IndexOutOfBoundsException(
					"feed 범위 오류: offset=" + offset + " len=" + len + " data=" + data.length);
		}
		ensureCapacity(size + len);
		System.arraycopy(data, offset, buffer, size, len);
		size += len;
	}

	public void feed(byte[] data) {
		feed(data, 0, data.length);
	}

	/**
	 * 완성된 전문 <b>본문</b> 하나를 꺼낸다(길이 헤더는 떼어 내고 본문만 반환). 아직 헤더나 본문이
	 * 덜 모였으면 {@code null}.
	 *
	 * @throws MalformedFrameException 길이 헤더가 숫자가 아니거나 상한을 초과할 때
	 */
	public byte[] next() throws MalformedFrameException {
		if (size < HEADER_LENGTH) {
			return null; // 1단계: 헤더가 아직 반쪽
		}
		int bodyLength = parseHeader();
		int frameLength = HEADER_LENGTH + bodyLength;
		if (size < frameLength) {
			return null; // 2단계: 본문이 아직 덜 참
		}

		byte[] body = Arrays.copyOfRange(buffer, HEADER_LENGTH, frameLength);
		int remaining = size - frameLength;
		// 남은 바이트(다음 전문의 앞부분일 수 있음)를 앞으로 당겨 압축한다.
		System.arraycopy(buffer, frameLength, buffer, 0, remaining);
		size = remaining;
		return body;
	}

	/** 헤더 4byte를 ASCII 십진수로 읽어 본문 길이를 돌려준다. 검증 실패면 즉시 예외. */
	private int parseHeader() throws MalformedFrameException {
		String header = new String(buffer, 0, HEADER_LENGTH, StandardCharsets.US_ASCII);
		int bodyLength = 0;
		for (int i = 0; i < HEADER_LENGTH; i++) {
			char c = header.charAt(i);
			if (c < '0' || c > '9') {
				throw new MalformedFrameException(
						"길이 헤더가 숫자가 아닙니다: '" + header + "' (비정상 프레임 — 연결을 끊습니다)");
			}
			bodyLength = bodyLength * 10 + (c - '0');
		}
		if (bodyLength > maxBodyLength) {
			throw new MalformedFrameException(String.format(
					"길이 헤더가 상한을 초과합니다: 요구 %d byte > 상한 %d byte (비정상 프레임 — 연결을 끊습니다)",
					bodyLength, maxBodyLength));
		}
		return bodyLength;
	}

	/** 현재 버퍼에 쌓인(아직 프레임으로 안 나간) 바이트 수. */
	public int buffered() {
		return size;
	}

	/**
	 * 프레임 경계에 못 미친 반쪽 바이트가 남아 있는가(연결이 전문 중간에 끊겼는지 판별용).
	 * 헤더가 덜 왔거나, 헤더는 왔지만 본문이 덜 온 상태를 모두 포함한다.
	 */
	public boolean hasPartial() throws MalformedFrameException {
		if (size == 0) {
			return false;
		}
		if (size < HEADER_LENGTH) {
			return true; // 헤더조차 반쪽
		}
		return size < HEADER_LENGTH + parseHeader(); // 본문 반쪽
	}

	private void ensureCapacity(int needed) {
		if (needed <= buffer.length) {
			return;
		}
		int newCap = buffer.length;
		while (newCap < needed) {
			newCap *= 2;
		}
		buffer = Arrays.copyOf(buffer, newCap);
	}

	/**
	 * 본문 byte[]를 <b>길이 헤더 + 본문</b>의 전선(wire) 바이트로 인코딩한다. 쓰는 쪽과 hex 표시가
	 * 같은 규칙을 쓰도록 한 곳에 둔다.
	 */
	public static byte[] encode(byte[] body) {
		if (body.length > 9999) {
			throw new IllegalArgumentException(
					"본문이 4byte ASCII 길이 헤더로 표현 가능한 9999 byte를 넘습니다: " + body.length);
		}
		byte[] header = String.format("%04d", body.length).getBytes(StandardCharsets.US_ASCII);
		byte[] wire = new byte[HEADER_LENGTH + body.length];
		System.arraycopy(header, 0, wire, 0, HEADER_LENGTH);
		System.arraycopy(body, 0, wire, HEADER_LENGTH, body.length);
		return wire;
	}
}
