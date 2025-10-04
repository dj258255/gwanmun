package io.gwanmun.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gwanmun.message.dto.BalanceInquiryRequest;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.gwanmun.message.spec.MessageSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 전문 파서/빌더의 왕복 무손실·인코딩·패딩·예외를 검증한다.
 * "바이트가 진실"이라는 원칙이 실제로 지켜지는지 byte[] 수준에서 확인한다.
 */
class MessageCodecTest {

	private static final Charset EUC_KR = Charset.forName("EUC-KR");

	private final MessageCodec codec = new MessageCodec();
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("스펙 총 길이가 필드 길이 합과 같다 (요청 52 / 응답 61 byte)")
	void specTotalLength() {
		// 요청 전문은 Phase 6에서 거래고유번호(22byte)가 붙어 30 → 52 byte가 됐다.
		assertThat(MessageSpec.of(BalanceInquiryRequest.class).totalLength()).isEqualTo(52);
		assertThat(MessageSpec.of(BalanceInquiryResponse.class).totalLength()).isEqualTo(61);
	}

	@Test
	@DisplayName("요청 전문 왕복 무손실: DTO → byte[] → DTO 가 동일")
	void requestRoundTrip() {
		BalanceInquiryRequest req = new BalanceInquiryRequest(
				"0200", "GWMNU20260709000000001", "12345678901234", "IN01", "");
		byte[] raw = codec.build(req);
		assertThat(raw).hasSize(52);

		BalanceInquiryRequest parsed = codec.parse(raw, BalanceInquiryRequest.class);
		assertThat(parsed).isEqualTo(req);

		// 다시 빌드해도 byte[]가 동일해야 진짜 무손실이다.
		assertThat(codec.build(parsed)).isEqualTo(raw);
	}

	@Test
	@DisplayName("응답 전문 왕복 무손실: byte[] → DTO → JSON → DTO → byte[] 가 원본과 동일")
	void responseRoundTripThroughJson() throws Exception {
		BalanceInquiryResponse res = new BalanceInquiryResponse(
				"0210", "12345678901234", "IN01", "1234567", "0000", "정상 처리되었습니다");
		byte[] raw = codec.build(res);
		assertThat(raw).hasSize(61);

		// byte[] → DTO
		BalanceInquiryResponse dto = codec.parse(raw, BalanceInquiryResponse.class);
		// DTO → JSON → DTO (Jackson 브릿지)
		String json = objectMapper.writeValueAsString(dto);
		BalanceInquiryResponse back = objectMapper.readValue(json, BalanceInquiryResponse.class);
		// DTO → byte[]
		byte[] rebuilt = codec.build(back);

		assertThat(back).isEqualTo(res);
		assertThat(rebuilt).isEqualTo(raw);
	}

	@Test
	@DisplayName("한글 필드 EUC-KR 왕복: 한글이 깨지지 않는다")
	void koreanRoundTripNotBroken() {
		String message = "잔액부족입니다";
		BalanceInquiryResponse res = new BalanceInquiryResponse(
				"0210", "00000000000001", "IN01", "0", "0001", message);
		byte[] raw = codec.build(res);

		BalanceInquiryResponse parsed = codec.parse(raw, BalanceInquiryResponse.class);
		assertThat(parsed.getResponseMessage()).isEqualTo(message);
	}

	@Test
	@DisplayName("한글은 EUC-KR에서 2byte다: length=20은 문자 20자가 아니라 20byte(한글 10자)")
	void koreanIsTwoBytesPerChar() {
		// 한글 6자 = 12 byte
		byte[] enc = "잔액부족입니".getBytes(EUC_KR);
		assertThat(enc).hasSize(12);

		// substring(문자 인덱스)으로 자르면 깨지는 것을, byte 슬라이스는 온전히 담는다.
		BalanceInquiryResponse res = new BalanceInquiryResponse(
				"0210", "00000000000001", "IN01", "0", "0001", "잔액부족입니");
		byte[] raw = codec.build(res);
		BalanceInquiryResponse parsed = codec.parse(raw, BalanceInquiryResponse.class);
		assertThat(parsed.getResponseMessage()).isEqualTo("잔액부족입니");
	}

	@Test
	@DisplayName("숫자 패딩: 우측 정렬 + 좌측 제로. 잔액 1234567 → 000000001234567(15자리)")
	void numericLeftZeroPadding() {
		BalanceInquiryResponse res = new BalanceInquiryResponse(
				"0210", "12345678901234", "IN01", "1234567", "0000", "정상");
		byte[] raw = codec.build(res);

		// 잔액 필드는 오프셋 22, 길이 15.
		String balanceField = new String(Arrays.copyOfRange(raw, 22, 37), StandardCharsets.US_ASCII);
		assertThat(balanceField).isEqualTo("000000001234567");

		// 파싱 시 좌측 0은 제거된다.
		assertThat(codec.parse(raw, BalanceInquiryResponse.class).getBalance()).isEqualTo("1234567");
	}

	@Test
	@DisplayName("문자 패딩: 좌측 정렬 + 우측 공백. filler(길이8)는 공백 8칸")
	void textRightSpacePadding() {
		BalanceInquiryRequest req = new BalanceInquiryRequest(
				"0200", "GWMNU20260709000000001", "1", "IN01", "");
		byte[] raw = codec.build(req);

		// filler 필드는 오프셋 44, 길이 8 (Phase 6: 거래ID 22byte가 앞에 끼어 오프셋이 밀렸다).
		String fillerField = new String(Arrays.copyOfRange(raw, 44, 52), StandardCharsets.US_ASCII);
		assertThat(fillerField).isEqualTo("        "); // 공백 8

		// 계좌번호 "1"은 좌측 제로패딩 → "00000000000001" (오프셋 26)
		String accountField = new String(Arrays.copyOfRange(raw, 26, 40), StandardCharsets.US_ASCII);
		assertThat(accountField).isEqualTo("00000000000001");
	}

	@Test
	@DisplayName("전부 0인 숫자 필드는 파싱 시 '0' 하나로 남는다")
	void allZeroNumericBecomesSingleZero() {
		BalanceInquiryResponse res = new BalanceInquiryResponse(
				"0210", "0", "IN01", "0", "0000", "정상");
		byte[] raw = codec.build(res);
		BalanceInquiryResponse parsed = codec.parse(raw, BalanceInquiryResponse.class);
		assertThat(parsed.getBalance()).isEqualTo("0");
		assertThat(parsed.getAccountNo()).isEqualTo("0");
	}

	@Test
	@DisplayName("길이 초과(숫자): 15자리 계좌번호를 14byte 필드에 → GwanmunBuildException")
	void buildFailsWhenNumericTooLong() {
		BalanceInquiryRequest req = new BalanceInquiryRequest(
				"0200", "GWMNU20260709000000001", "123456789012345", "IN01", "");
		assertThatThrownBy(() -> codec.build(req))
				.isInstanceOf(GwanmunBuildException.class)
				.hasMessageContaining("accountNo");
	}

	@Test
	@DisplayName("길이 초과(한글): 11자 한글은 22byte라 20byte 필드를 넘어 예외")
	void buildFailsWhenKoreanExceedsByteLength() {
		BalanceInquiryResponse res = new BalanceInquiryResponse(
				"0210", "1", "IN01", "0", "0000", "가나다라마바사아자차카"); // 11자 = 22byte
		assertThatThrownBy(() -> codec.build(res))
				.isInstanceOf(GwanmunBuildException.class)
				.hasMessageContaining("responseMessage");
	}

	@Test
	@DisplayName("잘린 전문(길이 미달): 파싱 시 GwanmunParseException")
	void parseFailsWhenTruncated() {
		byte[] truncated = new byte[20]; // 응답은 61byte 여야 함
		assertThatThrownBy(() -> codec.parse(truncated, BalanceInquiryResponse.class))
				.isInstanceOf(GwanmunParseException.class)
				.hasMessageContaining("길이 불일치");
	}

	@Test
	@DisplayName("과다 전문(길이 초과): 파싱 시 GwanmunParseException")
	void parseFailsWhenTooLong() {
		byte[] tooLong = new byte[62];
		assertThatThrownBy(() -> codec.parse(tooLong, BalanceInquiryResponse.class))
				.isInstanceOf(GwanmunParseException.class)
				.hasMessageContaining("길이 불일치");
	}

	@Test
	@DisplayName("EUC-KR 밖 문자(이모지 등): 무음 '?' 치환 대신 빌드가 GwanmunBuildException 으로 거절한다 (Phase 7)")
	void buildFailsClosedOnUnmappableCharacter() {
		// 수정 전 getBytes(charset)는 매핑 불가 문자를 조용히 '?'(0x3F)로 바꿔 내보냈다 —
		// 금융 전문에서 데이터가 소리 없이 바뀌는 무음 손상이다. 이제 fail-closed 로 거절한다.
		BalanceInquiryResponse res = new BalanceInquiryResponse(
				"0210", "12345678901234", "IN01", "0", "0000", "정상\uD83D\uDE00처리"); // 가운데 이모지
		assertThatThrownBy(() -> codec.build(res))
				.isInstanceOf(GwanmunBuildException.class)
				.hasMessageContaining("표현할 수 없는 문자");
	}

	@Test
	@DisplayName("NUMERIC 필드에 비숫자: 좌측 제로 패딩으로 내보내기 전에 빌드가 거절한다 (Phase 7)")
	void buildFailsOnNonDigitNumericField() {
		BalanceInquiryRequest req = new BalanceInquiryRequest(
				"0200", "GWMNU20260709000000001", "1234ABCD", "IN01", ""); // 계좌(NUMERIC)에 영문
		assertThatThrownBy(() -> codec.build(req))
				.isInstanceOf(GwanmunBuildException.class)
				.hasMessageContaining("accountNo")
				.hasMessageContaining("NUMERIC");
	}

	@Test
	@DisplayName("NUMERIC 빈 문자열은 여전히 허용된다 — 전부 제로 패딩(기존 계약 보존)")
	void buildAllowsEmptyNumeric() {
		BalanceInquiryRequest req = new BalanceInquiryRequest(
				"0200", "GWMNU20260709000000001", "", "IN01", "");
		byte[] raw = codec.build(req);
		// 계좌 필드(오프셋 26, 14byte)가 전부 '0' 이어야 한다.
		for (int i = 26; i < 40; i++) {
			assertThat(raw[i]).isEqualTo((byte) '0');
		}
	}
}
