package io.gwanmun.core;

import io.gwanmun.core.SettlementClient.SettlementResult;
import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.dto.BalanceInquiryRequest;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.gwanmun.message.dto.DailySettlementRecord;
import io.gwanmun.message.dto.NetCancelRequest;
import io.gwanmun.message.dto.TransactionStatusInquiryRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 당일 처리내역 조회(EOD 대사의 계정계측 전문, Phase 9)를 <b>실제 소켓 왕복</b>으로 검증한다.
 * 목업 계정계가 처리한 거래가 대사 응답에 그대로 나오고, 망취소한 원거래는 statusFlag=99로 뒤집힌다.
 */
class SettlementMockTest {

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

	private MockCoreBankingServer server;
	private CoreBankingClient balance;
	private SettlementClient settlement;
	private final MessageCodec codec = new MessageCodec();
	private String today;

	@BeforeEach
	void setUp() throws Exception {
		server = new MockCoreBankingServer(0, 0, 50); // 잔액·대사 둘 다 임의 포트, 짧은 지연.
		server.start();
		balance = new CoreBankingClient("127.0.0.1", server.port(), 1000, 1000);
		settlement = new SettlementClient("127.0.0.1", server.settlementPort(), 1000, 2000);
		today = LocalDate.now().format(YYYYMMDD);
	}

	@AfterEach
	void tearDown() {
		balance.close();
		server.close();
	}

	private String tranId(String date, int seq) {
		return String.format("GWMNU%s%09d", date, seq);
	}

	private long doBalance(String tranId, String accountNo) throws Exception {
		byte[] res = balance.exchange(
				codec.build(new BalanceInquiryRequest("0200", tranId, accountNo, "IN01", "")));
		return Long.parseLong(codec.parse(res, BalanceInquiryResponse.class).getBalance());
	}

	private Optional<DailySettlementRecord> find(SettlementResult r, String tranId) {
		return r.records().stream().filter(x -> x.getTranId().equals(tranId)).findFirst();
	}

	@Test
	@DisplayName("계정계가 처리한 거래가 당일 처리내역에 tranId·금액·상태(정상)로 나온다")
	void processedTransactionsAppearInSettlement() throws Exception {
		String t1 = tranId(today, 1);
		String t2 = tranId(today, 2);
		long bal1 = doBalance(t1, "12345678901234");
		long bal2 = doBalance(t2, "22223333444455");

		SettlementResult r = settlement.queryDay(today);

		assertThat(r.header().getResponseCode()).isEqualTo("0000");
		assertThat(find(r, t1)).hasValueSatisfying(rec -> {
			assertThat(rec.getStatusFlag()).isEqualTo(DailySettlementRecord.NORMAL);
			assertThat(Long.parseLong(rec.getAmount())).isEqualTo(bal1);
		});
		assertThat(find(r, t2)).hasValueSatisfying(rec ->
				assertThat(Long.parseLong(rec.getAmount())).isEqualTo(bal2));
	}

	@Test
	@DisplayName("망취소된 원거래는 당일 처리내역에서 statusFlag=99(취소)로 뒤집힌다")
	void canceledTransactionShowsAsCanceled() throws Exception {
		String t1 = tranId(today, 10);
		doBalance(t1, "98765432109876");

		// 상태조회(처리됨) → 망취소.
		balance.exchange(codec.build(new TransactionStatusInquiryRequest("0400", t1, "ST01", "")),
				TransactionKind.INQUIRY);
		balance.exchange(codec.build(new NetCancelRequest("0420", t1, "NC01", "")),
				TransactionKind.MUTATION);

		SettlementResult r = settlement.queryDay(today);
		assertThat(find(r, t1)).hasValueSatisfying(rec ->
				assertThat(rec.getStatusFlag()).isEqualTo(DailySettlementRecord.CANCELED));
	}

	@Test
	@DisplayName("다른 날짜의 거래는 당일 처리내역에 섞이지 않는다(거래고유번호 날짜 접두어로 거른다)")
	void filtersByDate() throws Exception {
		String todayTran = tranId(today, 20);
		String otherDay = LocalDate.now().minusDays(3).format(YYYYMMDD);
		String otherTran = tranId(otherDay, 20);
		doBalance(todayTran, "11112222333344");
		doBalance(otherTran, "55556666777788");

		SettlementResult r = settlement.queryDay(today);
		assertThat(find(r, todayTran)).isPresent();
		assertThat(find(r, otherTran)).isEmpty();
	}
}
