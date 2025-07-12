package io.gwanmun.message;

/**
 * 전문 바이트의 hex 문자열 인코딩/디코딩 + 사람이 읽는 hex 덤프 헬퍼.
 * ({@link java.util.HexFormat}이 있지만, 공백·개행 섞인 입력 정리와 아스키 병기 덤프가 필요해 얇게 감싼다.)
 */
public final class HexFormat2 {

	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private HexFormat2() {
	}

	/** byte[] → 대문자 hex 문자열(구분자 없음). */
	public static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
		}
		return sb.toString();
	}

	/**
	 * hex 문자열 → byte[]. 공백·개행·콜론은 무시한다(사람이 붙여넣은 덤프 허용).
	 *
	 * @throws GwanmunParseException 16진수가 아닌 문자·홀수 자릿수일 때
	 */
	public static byte[] fromHex(String hex) {
		if (hex == null) {
			throw new GwanmunParseException("hex 입력이 null 입니다.");
		}
		StringBuilder clean = new StringBuilder(hex.length());
		for (int i = 0; i < hex.length(); i++) {
			char c = hex.charAt(i);
			if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ':') {
				continue;
			}
			clean.append(c);
		}
		if (clean.length() % 2 != 0) {
			throw new GwanmunParseException("hex 자릿수가 홀수입니다: " + clean.length() + "자");
		}
		byte[] out = new byte[clean.length() / 2];
		for (int i = 0; i < out.length; i++) {
			int hi = digit(clean.charAt(i * 2));
			int lo = digit(clean.charAt(i * 2 + 1));
			out[i] = (byte) ((hi << 4) | lo);
		}
		return out;
	}

	private static int digit(char c) {
		int d = Character.digit(c, 16);
		if (d < 0) {
			throw new GwanmunParseException("16진수가 아닌 문자: '" + c + "'");
		}
		return d;
	}

	/**
	 * 사람이 읽는 아스키 병기 문자열. 인쇄 가능한 ASCII는 그대로, 나머지(한글 EUC-KR 등)는 '.'.
	 * (한글은 멀티바이트라 아스키 칸에는 온전히 안 보이지만, 어느 구간이 비-ASCII인지 눈으로 확인하는 용도.)
	 */
	public static String toAscii(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length);
		for (byte b : bytes) {
			int v = b & 0xFF;
			sb.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
		}
		return sb.toString();
	}
}
