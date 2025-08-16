package io.gwanmun.message;

import io.gwanmun.message.VariableMessageCodec.VariableMessage;
import io.gwanmun.message.dto.TransactionHistoryHeader;
import io.gwanmun.message.dto.TransactionRecord;
import io.gwanmun.message.spec.MessageSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가변 전문(고정 헤더 + 반복 레코드 N건) 코덱의 왕복 무손실과 방어를 검증한다.
 */
class VariableMessageCodecTest {

	private final VariableMessageCodec codec = new VariableMessageCodec();

	private static final int HEADER_LEN = MessageSpec.of(TransactionHistoryHeader.class).totalLength(); // 30
	private static final int RECORD_LEN = MessageSpec.of(TransactionRecord.class).totalLength();        // 55

	private List<TransactionRecord> sampleRecords(int n) {
		List<TransactionRecord> records = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			records.add(new TransactionRecord(
					Integer.toString(i + 1),
					"2026060" + ((i % 9) + 1),
					(i % 2 == 0) ? "입금" : "출금",
					Long.toString(10_000L * (i + 1)),
					Long.toString(1_000_000L + i),
					(i % 2 == 0) ? "급여이체" : "카드결제"));
		}
		return records;
	}

	private TransactionHistoryHeader header(String account, List<TransactionRecord> records) {
		int total = HEADER_LEN + records.size() * RECORD_LEN;
		return new TransactionHistoryHeader(
				"0310", account, Integer.toString(records.size()), Integer.toString(total), "0000");
	}

	@Test
	@DisplayName("레코드 5건 왕복 무손실: build → parse 후 헤더·레코드 모든 필드가 그대로")
	void roundTripFiveRecords() {
		List<TransactionRecord> records = sampleRecords(5);
		TransactionHistoryHeader head = header("12345678901234", records);

		byte[] body = codec.build(head, records);
		assertThat(body).hasSize(HEADER_LEN + 5 * RECORD_LEN); // 30 + 5*55 = 305

		VariableMessage<TransactionHistoryHeader, TransactionRecord> parsed =
				codec.parse(body, TransactionHistoryHeader.class, TransactionRecord.class);

		assertThat(parsed.header().getAccountNo()).isEqualTo("12345678901234");
		assertThat(parsed.header().getRecordCount()).isEqualTo("5");
		assertThat(parsed.header().getTotalLength()).isEqualTo("305");
		assertThat(parsed.records()).hasSize(5);
		assertThat(parsed.records().get(0).getTxType()).isEqualTo("입금");    // 한글 EUC-KR 왕복
		assertThat(parsed.records().get(0).getSummary()).isEqualTo("급여이체");
		assertThat(parsed.records().get(1).getTxType()).isEqualTo("출금");
		assertThat(parsed.records().get(4).getSeq()).isEqualTo("5");
	}

	@Test
	@DisplayName("건수가 다르면 전체 길이도 달라진다(가변): 0건·1건·12건")
	void variableCounts() {
		for (int n : new int[]{0, 1, 12}) {
			List<TransactionRecord> records = sampleRecords(n);
			byte[] body = codec.build(header("98765432109876", records), records);
			assertThat(body).hasSize(HEADER_LEN + n * RECORD_LEN);

			VariableMessage<TransactionHistoryHeader, TransactionRecord> parsed =
					codec.parse(body, TransactionHistoryHeader.class, TransactionRecord.class);
			assertThat(parsed.records()).hasSize(n);
		}
	}

	@Test
	@DisplayName("레코드가 딱 떨어지지 않으면(잘린 전문) 거절")
	void rejectsTruncatedRecordArea() {
		List<TransactionRecord> records = sampleRecords(3);
		byte[] body = codec.build(header("11112222333344", records), records);
		// 마지막 레코드의 꼬리 10byte를 잘라 낸다(레코드 경계가 안 맞게).
		byte[] truncated = Arrays.copyOf(body, body.length - 10);

		assertThatThrownBy(() -> codec.parse(truncated, TransactionHistoryHeader.class, TransactionRecord.class))
				.isInstanceOf(GwanmunParseException.class)
				.hasMessageContaining("나눠떨어지지");
	}

	@Test
	@DisplayName("본문이 헤더보다 짧으면 거절")
	void rejectsShorterThanHeader() {
		byte[] tooShort = new byte[HEADER_LEN - 1];
		assertThatThrownBy(() -> codec.parse(tooShort, TransactionHistoryHeader.class, TransactionRecord.class))
				.isInstanceOf(GwanmunParseException.class)
				.hasMessageContaining("헤더보다 짧");
	}
}
