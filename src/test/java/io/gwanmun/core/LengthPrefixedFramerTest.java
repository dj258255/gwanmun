package io.gwanmun.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가변 프레이밍 누적기의 경계 복원을 검증한다. TCP 스트림이 헤더든 본문이든 어떻게 쪼개지고 뭉쳐
 * 오든, "완성된 본문"만 나오고 비정상 길이는 거절돼야 한다. Phase 4의 핵심 불변식.
 */
class LengthPrefixedFramerTest {

	/** 본문 body를 "4byte ASCII 길이 헤더 + body"의 전선 바이트로 만든다. */
	private static byte[] wire(byte[] body) {
		return LengthPrefixedFramer.encode(body);
	}

	private static byte[] body(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	@Test
	@DisplayName("한 번에 딱 맞게 오면 본문 하나(헤더는 떼고 반환)")
	void exactSingleFrame() throws Exception {
		LengthPrefixedFramer framer = new LengthPrefixedFramer(9999);
		byte[] b = body("HELLO-VARIABLE");

		framer.feed(wire(b));
		assertThat(framer.next()).isEqualTo(b);
		assertThat(framer.next()).isNull();
		assertThat(framer.buffered()).isZero();
	}

	@Test
	@DisplayName("헤더 반쪽 도착: 4byte 헤더가 다 와야 본문 길이를 읽는다(1단계)")
	void partialHeader() throws Exception {
		LengthPrefixedFramer framer = new LengthPrefixedFramer(9999);
		byte[] w = wire(body("ABCDE")); // 헤더 "0005" + 본문 5byte

		// 헤더 4byte 중 2byte("00")만 도착 — 아직 길이조차 못 읽는다.
		framer.feed(w, 0, 2);
		assertThat(framer.next()).isNull();
		assertThat(framer.hasPartial()).isTrue();

		// 나머지가 다 와야 본문이 나온다.
		framer.feed(w, 2, w.length - 2);
		assertThat(framer.next()).isEqualTo(body("ABCDE"));
	}

	@Test
	@DisplayName("본문 반쪽 도착: 헤더로 길이를 안 뒤 본문이 다 모여야 완성(2단계)")
	void partialBody() throws Exception {
		LengthPrefixedFramer framer = new LengthPrefixedFramer(9999);
		byte[] b = body("PART-OF-BODY-123");
		byte[] w = wire(b); // "0016" + 16byte

		// 헤더 + 본문 앞 10byte만 — 헤더는 완성이나 본문이 덜 왔다.
		framer.feed(w, 0, LengthPrefixedFramer.HEADER_LENGTH + 10);
		assertThat(framer.next()).isNull();
		assertThat(framer.hasPartial()).isTrue();

		// 본문 나머지 6byte 도착 → 완성.
		framer.feed(w, LengthPrefixedFramer.HEADER_LENGTH + 10, 6);
		assertThat(framer.next()).isEqualTo(b);
	}

	@Test
	@DisplayName("한 바이트씩 극단적으로 쪼개 와도 정확히 재조립된다")
	void byteByByteReassembly() throws Exception {
		LengthPrefixedFramer framer = new LengthPrefixedFramer(9999);
		byte[] b = body("DRIP-DRIP-DRIP");
		byte[] w = wire(b);

		for (int i = 0; i < w.length - 1; i++) {
			framer.feed(w, i, 1);
			assertThat(framer.next()).as("마지막 바이트 전까지는 미완성").isNull();
		}
		framer.feed(w, w.length - 1, 1);
		assertThat(framer.next()).isEqualTo(b);
	}

	@Test
	@DisplayName("뭉침: 길이가 다른 세 전문이 붙어 와도 각각의 본문으로 분리된다")
	void multipleFramesInOneChunk() throws Exception {
		LengthPrefixedFramer framer = new LengthPrefixedFramer(9999);
		byte[] b1 = body("one");
		byte[] b2 = body("two-longer");
		byte[] b3 = body("three-even-longer!!");

		byte[] w1 = wire(b1), w2 = wire(b2), w3 = wire(b3);
		byte[] glued = new byte[w1.length + w2.length + w3.length];
		System.arraycopy(w1, 0, glued, 0, w1.length);
		System.arraycopy(w2, 0, glued, w1.length, w2.length);
		System.arraycopy(w3, 0, glued, w1.length + w2.length, w3.length);

		framer.feed(glued);
		assertThat(framer.next()).isEqualTo(b1);
		assertThat(framer.next()).isEqualTo(b2);
		assertThat(framer.next()).isEqualTo(b3);
		assertThat(framer.next()).isNull();
	}

	@Test
	@DisplayName("한 전문 반: 앞 전문을 꺼낸 뒤 남은 반쪽은 보존되어 다음 조각과 합쳐진다")
	void oneAndHalfFrame() throws Exception {
		LengthPrefixedFramer framer = new LengthPrefixedFramer(9999);
		byte[] b1 = body("first-message");
		byte[] b2 = body("second-message-body");
		byte[] w1 = wire(b1), w2 = wire(b2);

		// w1 전체 + w2의 앞 5byte 가 뭉쳐 도착.
		byte[] chunk = new byte[w1.length + 5];
		System.arraycopy(w1, 0, chunk, 0, w1.length);
		System.arraycopy(w2, 0, chunk, w1.length, 5);
		framer.feed(chunk);

		assertThat(framer.next()).isEqualTo(b1);
		assertThat(framer.next()).isNull(); // b2는 아직 덜 왔다

		framer.feed(w2, 5, w2.length - 5);
		assertThat(framer.next()).isEqualTo(b2);
	}

	@Test
	@DisplayName("빈 본문(길이 0)도 정상 프레임으로 처리한다")
	void zeroLengthBody() throws Exception {
		LengthPrefixedFramer framer = new LengthPrefixedFramer(9999);
		byte[] w = "0000".getBytes(StandardCharsets.US_ASCII); // 헤더 "0000", 본문 없음
		framer.feed(w);
		assertThat(framer.next()).isEmpty();
		assertThat(framer.next()).isNull();
	}

	@Test
	@DisplayName("비정상 길이 — 헤더가 숫자가 아니면(손상 바이트) 거절")
	void rejectsNonNumericHeader() {
		LengthPrefixedFramer framer = new LengthPrefixedFramer(9999);
		framer.feed("AB!!xxxx".getBytes(StandardCharsets.US_ASCII));
		assertThatThrownBy(framer::next)
				.isInstanceOf(MalformedFrameException.class)
				.hasMessageContaining("숫자가 아닙니다");
	}

	@Test
	@DisplayName("비정상 길이 — 헤더가 상한을 넘는 크기를 요구하면 거절(자원 고갈 방어)")
	void rejectsOversizedHeader() {
		LengthPrefixedFramer framer = new LengthPrefixedFramer(100); // 본문 상한 100byte
		framer.feed("9999".getBytes(StandardCharsets.US_ASCII));     // 9999byte를 요구
		assertThatThrownBy(framer::next)
				.isInstanceOf(MalformedFrameException.class)
				.hasMessageContaining("상한을 초과");
	}

	@Test
	@DisplayName("상한을 넘는 본문은 encode 단계에서도 막는다")
	void encodeRejectsTooLongBody() {
		byte[] tooLong = new byte[10000];
		assertThatThrownBy(() -> LengthPrefixedFramer.encode(tooLong))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("잘못된 상한(0 이하, 9999 초과)은 생성 시 거부")
	void rejectsBadMaxBody() {
		assertThatThrownBy(() -> new LengthPrefixedFramer(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LengthPrefixedFramer(10000))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
