package io.gwanmun.message;

import io.gwanmun.message.spec.FieldSpec;
import io.gwanmun.message.spec.FieldType;
import io.gwanmun.message.spec.MessageSpec;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;

/**
 * 고정길이 전문 ↔ DTO 양방향 변환 엔진. 이 프로젝트의 심장.
 *
 * <p>핵심 원칙: <b>바이트가 진실이다.</b> 자르기·패딩·트림을 전부 byte[] 위에서 한다.
 * 문자열(String)로 substring을 하면 한글(EUC-KR 2byte)이 경계에서 깨지기 때문이다.
 * 그래서 슬라이스도 byte 오프셋으로, 패딩 제거도 byte 단위로 먼저 한 뒤 <b>마지막에만</b> 디코딩한다.
 *
 * <p>패딩·정렬 관례(금융 전문의 오래된 관습):
 * <ul>
 *   <li>NUMERIC: 우측 정렬 → 좌측 제로('0', 0x30) 패딩. 파싱 시 좌측 0 제거.</li>
 *   <li>TEXT: 좌측 정렬 → 우측 공백(' ', 0x20) 패딩. 파싱 시 우측 공백 제거.</li>
 * </ul>
 */
public class MessageCodec {

	/** 전문 인코딩. 한글이 2byte인 EUC-KR로 고정(금융/공공 전문의 관례). */
	public static final Charset EUC_KR = Charset.forName("EUC-KR");

	private static final byte ZERO = (byte) '0';   // 0x30, 숫자 좌측 패딩
	private static final byte SPACE = (byte) ' ';  // 0x20, 문자 우측 패딩

	private final Charset charset;

	public MessageCodec() {
		this(EUC_KR);
	}

	public MessageCodec(Charset charset) {
		this.charset = charset;
	}

	// ---------------------------------------------------------------------
	// 파싱: byte[] → DTO
	// ---------------------------------------------------------------------

	/**
	 * 전문 바이트를 DTO로 파싱한다.
	 *
	 * @throws GwanmunParseException 전체 길이가 스펙과 다를 때(잘린 전문·과다 전문)
	 */
	public <T> T parse(byte[] raw, Class<T> dtoType) {
		if (raw == null) {
			throw new GwanmunParseException("전문 바이트가 null 입니다.");
		}
		MessageSpec spec = MessageSpec.of(dtoType);
		if (raw.length != spec.totalLength()) {
			throw new GwanmunParseException(String.format(
					"전문 길이 불일치: %s 스펙은 %d byte 인데 입력은 %d byte 입니다.",
					dtoType.getSimpleName(), spec.totalLength(), raw.length));
		}

		T dto = newInstance(dtoType);
		for (FieldSpec field : spec.fields()) {
			byte[] slice = Arrays.copyOfRange(raw, field.offset(), field.endOffset());
			byte[] trimmed = stripPadding(slice, field.type());
			String value = new String(trimmed, charset);
			setValue(dto, field, value);
		}
		return dto;
	}

	/** 타입별 패딩을 byte 단위로 제거한다(디코딩 전에 해야 한글이 안 깨진다). */
	private byte[] stripPadding(byte[] slice, FieldType type) {
		if (type == FieldType.NUMERIC) {
			// 좌측 제로 제거. 전부 0이면 "0" 하나를 남긴다.
			int start = 0;
			while (start < slice.length - 1 && slice[start] == ZERO) {
				start++;
			}
			return Arrays.copyOfRange(slice, start, slice.length);
		}
		// TEXT: 우측 공백 제거.
		// EUC-KR 멀티바이트의 두 바이트는 모두 0xA1~0xFE 범위라 0x20(공백)이 절대 섞이지 않는다.
		// 따라서 끝에서부터 0x20 바이트를 떼어내도 한글이 깨질 위험이 없다.
		int end = slice.length;
		while (end > 0 && slice[end - 1] == SPACE) {
			end--;
		}
		return Arrays.copyOfRange(slice, 0, end);
	}

	// ---------------------------------------------------------------------
	// 빌드: DTO → byte[]
	// ---------------------------------------------------------------------

	/**
	 * DTO를 고정길이 전문 바이트로 만든다.
	 *
	 * @throws GwanmunBuildException 어떤 필드 값의 인코딩 결과가 필드 길이를 초과할 때,
	 *                               NUMERIC 필드에 숫자가 아닌 문자가 있을 때,
	 *                               전문 인코딩으로 표현할 수 없는 문자가 있을 때(무음 '?' 치환 금지)
	 */
	public byte[] build(Object dto) {
		MessageSpec spec = MessageSpec.of(dto.getClass());
		byte[] out = new byte[spec.totalLength()];

		for (FieldSpec field : spec.fields()) {
			String value = getValue(dto, field);
			if (field.type() == FieldType.NUMERIC && !isDigits(value)) {
				// NUMERIC은 좌측 제로 패딩과 함께 "숫자"라는 계약이다. 비숫자를 통과시키면 상대측
				// 파서가 깨지거나(파싱 오류) 조용히 다른 값으로 읽힌다 — 나가기 전에 거절한다(Phase 7).
				throw new GwanmunBuildException(String.format(
						"필드 '%s' 는 NUMERIC(숫자만) 인데 숫자가 아닌 문자가 있습니다.", field.name()));
			}
			byte[] encoded = encode(value, field);
			if (encoded.length > field.length()) {
				throw new GwanmunBuildException(String.format(
						"필드 '%s' 값이 너무 깁니다: 인코딩 후 %d byte 인데 최대 %d byte 입니다. (값='%s')",
						field.name(), encoded.length, field.length(), value));
			}
			placeField(out, field, encoded);
		}
		return out;
	}

	/** NUMERIC 필드 검증 — 빈 문자열은 허용한다(전부 제로 패딩 → 파싱 시 "0"). */
	private static boolean isDigits(String value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c < '0' || c > '9') {
				return false;
			}
		}
		return true;
	}

	/**
	 * 값을 전문 인코딩으로 fail-closed 인코딩한다(Phase 7). {@code String.getBytes(charset)}는 매핑
	 * 불가 문자(이모지 등 EUC-KR 밖 문자)를 <b>조용히 '?'로 치환</b>한다 — 금융 전문에서 데이터가
	 * 소리 없이 바뀌는 것은 실패보다 나쁘다. {@link CodingErrorAction#REPORT}로 예외로 드러낸다.
	 */
	private byte[] encode(String value, FieldSpec field) {
		CharsetEncoder encoder = charset.newEncoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value));
			byte[] bytes = new byte[encoded.remaining()];
			encoded.get(bytes);
			return bytes;
		} catch (CharacterCodingException e) {
			throw new GwanmunBuildException(String.format(
					"필드 '%s' 값에 %s 로 표현할 수 없는 문자가 있습니다 — '?' 무음 치환 대신 거절합니다.",
					field.name(), charset.name()), e);
		}
	}

	/** 인코딩된 값 바이트를 타입 관례에 맞춰 정렬·패딩하여 출력 버퍼에 채운다. */
	private void placeField(byte[] out, FieldSpec field, byte[] encoded) {
		int base = field.offset();
		int pad = field.length() - encoded.length;
		if (field.type() == FieldType.NUMERIC) {
			// 우측 정렬 + 좌측 제로 패딩.
			Arrays.fill(out, base, base + pad, ZERO);
			System.arraycopy(encoded, 0, out, base + pad, encoded.length);
		} else {
			// 좌측 정렬 + 우측 공백 패딩.
			System.arraycopy(encoded, 0, out, base, encoded.length);
			Arrays.fill(out, base + encoded.length, base + field.length(), SPACE);
		}
	}

	// ---------------------------------------------------------------------
	// 리플렉션 헬퍼 (모든 전문 필드는 String)
	// ---------------------------------------------------------------------

	private <T> T newInstance(Class<T> dtoType) {
		try {
			Constructor<T> ctor = dtoType.getDeclaredConstructor();
			ctor.setAccessible(true);
			return ctor.newInstance();
		} catch (ReflectiveOperationException e) {
			throw new GwanmunParseException(
					dtoType.getName() + " 에 접근 가능한 무인자 생성자가 필요합니다.", e);
		}
	}

	private String getValue(Object dto, FieldSpec field) {
		try {
			Object raw = field.javaField().get(dto);
			return raw == null ? "" : raw.toString();
		} catch (IllegalAccessException e) {
			throw new GwanmunBuildException("필드 '" + field.name() + "' 값을 읽지 못했습니다.", e);
		}
	}

	private void setValue(Object dto, FieldSpec field, String value) {
		try {
			field.javaField().set(dto, value);
		} catch (IllegalAccessException e) {
			throw new GwanmunParseException("필드 '" + field.name() + "' 값을 설정하지 못했습니다.", e);
		}
	}
}
