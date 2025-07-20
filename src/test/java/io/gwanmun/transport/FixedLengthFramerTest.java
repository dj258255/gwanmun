package io.gwanmun.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 프레이밍 누적기의 경계 복원을 검증한다. TCP 스트림이 어떻게 쪼개지고 뭉쳐 오든
 * "완성된 고정길이 프레임"만 나와야 한다. 이 프로젝트 Phase 2의 핵심 불변식.
 */
class FixedLengthFramerTest {

	/** 0..len-1 값을 채운 테스트용 프레임(경계가 섞였는지 눈으로 확인 가능). */
	private static byte[] frame(int len, int base) {
		byte[] f = new byte[len];
		for (int i = 0; i < len; i++) {
			f[i] = (byte) (base + i);
		}
		return f;
	}

	@Test
	@DisplayName("한 번에 딱 맞게 오면 그대로 한 프레임")
	void exactSingleFrame() {
		FixedLengthFramer framer = new FixedLengthFramer(61);
		byte[] f = frame(61, 0);

		framer.feed(f);
		assertThat(framer.next()).isEqualTo(f);
		assertThat(framer.next()).isNull();
		assertThat(framer.buffered()).isZero();
	}

	@Test
	@DisplayName("반쪽 도착(partial read): 30byte만 오면 아직 프레임 없음, 나머지 31byte가 와야 완성")
	void partialThenComplete() {
		FixedLengthFramer framer = new FixedLengthFramer(61);
		byte[] full = frame(61, 0);

		// 앞 30byte만 도착 — 아직 한 프레임이 안 된다.
		framer.feed(full, 0, 30);
		assertThat(framer.next()).isNull();
		assertThat(framer.hasPartial()).isTrue();
		assertThat(framer.buffered()).isEqualTo(30);

		// 나머지 31byte 도착 — 이제 원본과 동일한 프레임이 완성된다.
		framer.feed(full, 30, 31);
		byte[] out = framer.next();
		assertThat(out).isEqualTo(full);
		assertThat(framer.buffered()).isZero();
	}

	@Test
	@DisplayName("한 바이트씩 극단적으로 쪼개 와도 정확히 재조립된다")
	void byteByByteReassembly() {
		FixedLengthFramer framer = new FixedLengthFramer(61);
		byte[] full = frame(61, 7);

		for (int i = 0; i < 60; i++) {
			framer.feed(full, i, 1);
			assertThat(framer.next()).as("60번째 전까지는 미완성").isNull();
		}
		framer.feed(full, 60, 1); // 마지막 한 바이트
		assertThat(framer.next()).isEqualTo(full);
	}

	@Test
	@DisplayName("뭉침: 두 전문이 붙어 한 번에 와도 두 프레임으로 분리된다")
	void twoFramesInOneChunk() {
		FixedLengthFramer framer = new FixedLengthFramer(61);
		byte[] a = frame(61, 0);
		byte[] b = frame(61, 100);

		byte[] glued = new byte[122];
		System.arraycopy(a, 0, glued, 0, 61);
		System.arraycopy(b, 0, glued, 61, 61);

		framer.feed(glued);
		assertThat(framer.next()).isEqualTo(a);
		assertThat(framer.next()).isEqualTo(b);
		assertThat(framer.next()).isNull();
	}

	@Test
	@DisplayName("한 프레임 반: 프레임 하나 꺼낸 뒤 남은 반쪽은 버퍼에 보존되어 다음 조각과 합쳐진다")
	void oneAndHalfFrame() {
		FixedLengthFramer framer = new FixedLengthFramer(61);
		byte[] a = frame(61, 0);
		byte[] b = frame(61, 200);

		// a 전체 + b의 앞 20byte 가 뭉쳐 도착.
		byte[] chunk = new byte[81];
		System.arraycopy(a, 0, chunk, 0, 61);
		System.arraycopy(b, 0, chunk, 61, 20);
		framer.feed(chunk);

		assertThat(framer.next()).isEqualTo(a);        // a 완성
		assertThat(framer.next()).isNull();            // b는 아직 20byte뿐
		assertThat(framer.buffered()).isEqualTo(20);

		// b의 나머지 41byte 도착 → b 완성.
		framer.feed(b, 20, 41);
		assertThat(framer.next()).isEqualTo(b);
	}

	@Test
	@DisplayName("버퍼가 초기 용량을 넘겨도(여러 프레임 누적) 정확히 커진다")
	void growsBeyondInitialCapacity() {
		FixedLengthFramer framer = new FixedLengthFramer(30);
		byte[] all = new byte[30 * 10];
		for (int k = 0; k < 10; k++) {
			byte[] f = frame(30, k * 3);
			System.arraycopy(f, 0, all, k * 30, 30);
		}
		framer.feed(all); // 300byte 한 번에

		for (int k = 0; k < 10; k++) {
			byte[] expected = frame(30, k * 3);
			assertThat(framer.next()).as("%d번째 프레임", k).isEqualTo(expected);
		}
		assertThat(framer.next()).isNull();
	}

	@Test
	@DisplayName("잘못된 프레임 길이(0 이하)는 생성 시 거부")
	void rejectsNonPositiveFrameLength() {
		assertThatThrownBy(() -> new FixedLengthFramer(0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("feed 범위를 벗어나면 예외")
	void rejectsBadFeedRange() {
		FixedLengthFramer framer = new FixedLengthFramer(10);
		byte[] data = new byte[5];
		assertThatThrownBy(() -> framer.feed(data, 0, 6))
				.isInstanceOf(IndexOutOfBoundsException.class);
	}

	@Test
	@DisplayName("남은 반쪽은 hasPartial=true, 정확히 프레임 경계면 false")
	void partialFlag() {
		FixedLengthFramer framer = new FixedLengthFramer(10);
		assertThat(framer.hasPartial()).isFalse(); // 빈 상태
		framer.feed(new byte[4]);
		assertThat(framer.hasPartial()).isTrue();
		framer.feed(new byte[6]);
		assertThat(Arrays.copyOf(framer.next(), 10)).hasSize(10);
		assertThat(framer.hasPartial()).isFalse();
	}
}
