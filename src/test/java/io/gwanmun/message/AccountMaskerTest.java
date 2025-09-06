package io.gwanmun.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 계좌 마스킹 규칙(앞6+뒤4만 노출)을 못 박는다 — 원장·앱 로그에 원문이 남지 않는 근거.
 */
class AccountMaskerTest {

	@Test
	@DisplayName("14자리 계좌: 앞6 + **** + 뒤4 만 노출")
	void mask14() {
		assertThat(AccountMasker.mask("12345678901234")).isEqualTo("123456****1234");
	}

	@Test
	@DisplayName("11자리: 앞6 + * + 뒤4 (가운데 자릿수만큼 가림)")
	void mask11() {
		assertThat(AccountMasker.mask("12345678901")).isEqualTo("123456*8901");
		// 원문 길이는 보존된다(자릿수 정보는 남고 가운데만 가려진다).
		assertThat(AccountMasker.mask("12345678901")).hasSize(11);
	}

	@Test
	@DisplayName("7~10자리: 노출을 앞2+뒤2로 줄인다")
	void maskShort() {
		assertThat(AccountMasker.mask("1234567890")).isEqualTo("12******90");
		assertThat(AccountMasker.mask("1234567")).isEqualTo("12***67");
	}

	@Test
	@DisplayName("6자리 이하: 전부 가린다(노출 구간이 성립하지 않음)")
	void maskTiny() {
		assertThat(AccountMasker.mask("123456")).isEqualTo("******");
		assertThat(AccountMasker.mask("7")).isEqualTo("*");
	}

	@Test
	@DisplayName("null·공백은 '-' — 예외 없이 안전하게")
	void maskBlank() {
		assertThat(AccountMasker.mask(null)).isEqualTo("-");
		assertThat(AccountMasker.mask("  ")).isEqualTo("-");
	}

	@Test
	@DisplayName("숫자가 아닌 입력(검증 실패로 들어온 원문)도 같은 규칙으로 가린다")
	void maskNonNumeric() {
		assertThat(AccountMasker.mask("ABCDEFGHIJKLMN")).isEqualTo("ABCDEF****KLMN");
	}
}
