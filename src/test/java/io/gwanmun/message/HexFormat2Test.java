package io.gwanmun.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** hex 인코딩/디코딩 왕복과 사람이 붙여넣은 덤프(공백·개행) 관용 처리 검증. */
class HexFormat2Test {

	@Test
	@DisplayName("hex 왕복: bytes → hex → bytes 무손실")
	void hexRoundTrip() {
		byte[] original = {0x00, 0x30, (byte) 0xA1, (byte) 0xFE, 0x7F};
		String hex = HexFormat2.toHex(original);
		assertThat(hex).isEqualTo("0030A1FE7F");
		assertThat(HexFormat2.fromHex(hex)).isEqualTo(original);
	}

	@Test
	@DisplayName("공백·개행·콜론이 섞인 hex도 파싱한다")
	void fromHexIgnoresSeparators() {
		byte[] parsed = HexFormat2.fromHex("30 32\n30:30");
		assertThat(parsed).isEqualTo(new byte[]{0x30, 0x32, 0x30, 0x30});
	}

	@Test
	@DisplayName("홀수 자릿수 hex는 예외")
	void fromHexRejectsOddLength() {
		assertThatThrownBy(() -> HexFormat2.fromHex("30A"))
				.isInstanceOf(GwanmunParseException.class)
				.hasMessageContaining("홀수");
	}

	@Test
	@DisplayName("16진수가 아닌 문자는 예외")
	void fromHexRejectsNonHex() {
		assertThatThrownBy(() -> HexFormat2.fromHex("3G"))
				.isInstanceOf(GwanmunParseException.class);
	}

	@Test
	@DisplayName("아스키 병기: 인쇄 가능 문자는 그대로, 나머지는 점")
	void asciiDump() {
		byte[] bytes = {0x41, 0x42, 0x00, (byte) 0xA1}; // 'A' 'B' NUL 비인쇄
		assertThat(HexFormat2.toAscii(bytes)).isEqualTo("AB..");
	}
}
