package io.gwanmun.message;

/**
 * 계좌번호 마스킹 — 원장·앱 로그에 계좌 원문이 남지 않게 한다.
 *
 * <p><b>규칙(명시)</b>: <b>앞 6자리 + 뒤 4자리만 노출</b>하고 가운데는 전부 {@code *}로 가린다.
 * 14자리 계좌 {@code 12345678901234} → {@code 123456****1234}. 노출 구간이 성립하지 않는 짧은
 * 입력은 더 보수적으로 가린다.
 *
 * <ul>
 *   <li>11자리 이상 — 앞6 + {@code *}×(len-10) + 뒤4</li>
 *   <li>7~10자리 — 앞2 + {@code *}×(len-4) + 뒤2 (노출을 4자로 줄임)</li>
 *   <li>6자리 이하 — 전부 {@code *} (노출 없음)</li>
 *   <li>null·공백 — {@code "-"}</li>
 * </ul>
 *
 * <p>입력 검증 실패로 숫자가 아닌 문자열이 와도 같은 규칙으로 가린다(원문 노출 방지가 우선).
 */
public final class AccountMasker {

	private AccountMasker() {
	}

	public static String mask(String accountNo) {
		if (accountNo == null || accountNo.isBlank()) {
			return "-";
		}
		String s = accountNo.trim();
		int len = s.length();
		if (len >= 11) {
			return s.substring(0, 6) + "*".repeat(len - 10) + s.substring(len - 4);
		}
		if (len >= 7) {
			return s.substring(0, 2) + "*".repeat(len - 4) + s.substring(len - 2);
		}
		return "*".repeat(len);
	}
}
