package io.gwanmun.core;

import java.util.Arrays;

/**
 * 고정길이 전문의 프레이밍(경계 복원) 누적기. Phase 2의 핵심.
 *
 * <p><b>왜 필요한가.</b> TCP는 바이트 스트림이라 "한 전문 = 고정 61byte"라도
 * 소켓 {@code read()} 한 번이 그 61byte를 온전히 준다는 보장이 없다.
 * <ul>
 *   <li><b>반쪽(partial read)</b>: 30byte만 오고 나머지 31byte는 다음 read에 온다.</li>
 *   <li><b>뭉침</b>: 두 전문이 붙어 122byte가 한 번에 올 수도 있다.</li>
 * </ul>
 * 그래서 "필요한 바이트가 다 모일 때까지 버퍼에 쌓아두고, 프레임 길이만큼 찼을 때에만
 * 한 전문으로 잘라 내보낸다". 이 누적·경계 관리를 손으로 하는 것이 프레이밍이다.
 *
 * <p>이 구현은 <b>고정길이</b> 프레이밍이다(프레임 길이를 상수로 안다). 가변길이라면
 * 앞머리에 길이 헤더를 두고 그 값을 읽어 경계를 잡는데, 우리 전문은 종류별로 길이가
 * 고정이라 상수로 충분하다(로드맵의 "고정길이면 길이 상수" 방침).
 *
 * <p>스레드 안전하지 않다. 한 연결(소켓)당 하나씩 쓰는 것을 전제로 한다.
 */
public final class FixedLengthFramer {

	private final int frameLength;
	private byte[] buffer;
	private int size; // buffer[0..size) 가 유효한 누적 바이트

	public FixedLengthFramer(int frameLength) {
		if (frameLength <= 0) {
			throw new IllegalArgumentException("프레임 길이는 1 이상이어야 합니다: " + frameLength);
		}
		this.frameLength = frameLength;
		this.buffer = new byte[Math.max(frameLength * 2, 64)];
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
	 * 완성된 전문 하나를 꺼낸다. 아직 프레임 길이만큼 안 모였으면 {@code null}.
	 *
	 * <p>뭉침 상황을 위해, 한 번 feed 뒤 {@code null}이 나올 때까지 반복 호출하면
	 * 버퍼에 쌓인 완성 프레임을 모두 순서대로 꺼낼 수 있다.
	 */
	public byte[] next() {
		if (size < frameLength) {
			return null;
		}
		byte[] frame = Arrays.copyOfRange(buffer, 0, frameLength);
		int remaining = size - frameLength;
		// 남은 바이트(다음 전문의 앞부분일 수 있음)를 앞으로 당겨 압축한다.
		System.arraycopy(buffer, frameLength, buffer, 0, remaining);
		size = remaining;
		return frame;
	}

	/** 현재 버퍼에 쌓인(아직 프레임으로 안 나간) 바이트 수. */
	public int buffered() {
		return size;
	}

	/** 프레임 경계에 못 미친 반쪽 바이트가 남아 있는가(연결이 전문 중간에 끊겼는지 판별용). */
	public boolean hasPartial() {
		return size > 0 && size < frameLength;
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
}
