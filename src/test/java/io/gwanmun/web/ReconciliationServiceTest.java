package io.gwanmun.web;

import io.gwanmun.core.SettlementClient;
import io.gwanmun.core.SettlementClient.SettlementResult;
import io.gwanmun.gateway.GatewayException;
import io.gwanmun.gateway.TransactionResolutionService;
import io.gwanmun.gateway.TransactionResolutionService.Resolution;
import io.gwanmun.gateway.TransactionResolutionService.ResolutionOutcome;
import io.gwanmun.ledger.ReconciliationLog;
import io.gwanmun.ledger.TransactionLedger;
import io.gwanmun.ledger.TransactionLedger.LedgerView;
import io.gwanmun.ledger.TransactionStatus;
import io.gwanmun.message.dto.DailySettlementHeader;
import io.gwanmun.message.dto.DailySettlementRecord;
import io.gwanmun.web.ReconciliationService.ReconType;
import io.gwanmun.web.ReconciliationService.ReconEntry;
import io.gwanmun.web.ReconciliationService.ReconciliationReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EOD 대사의 <b>4유형 분류</b>와 <b>UNKNOWN 자동 해소</b>를 협력자를 목킹해 결정론적으로 검증한다.
 * 계정계·원장·해소를 실제로 왕복하지 않고, 대사 로직 자체(분류·해소 매핑)만 격리해서 본다.
 */
class ReconciliationServiceTest {

	private static final String DATE = "20260709";

	private final SettlementClient settlement = mock(SettlementClient.class);
	private final TransactionLedger ledger = mock(TransactionLedger.class);
	private final TransactionResolutionService resolution = mock(TransactionResolutionService.class);
	private final ReconciliationLog reconLog = mock(ReconciliationLog.class);
	private final ReconciliationService service = new ReconciliationService(
			settlement, ledger, resolution, reconLog, new SimpleMeterRegistry());

	private static LedgerView ledgerView(String tranId, TransactionStatus status, Long amount) {
		return new LedgerView(tranId, "IN01", "123456****1234", status, "0000", null,
				Instant.now(), 5, "cid", null, null, amount);
	}

	private static DailySettlementRecord coreRec(String tranId, long amount, boolean canceled) {
		return new DailySettlementRecord(tranId, "12345678901234", Long.toString(amount),
				canceled ? DailySettlementRecord.CANCELED : DailySettlementRecord.NORMAL);
	}

	private void coreReturns(DailySettlementRecord... records) {
		DailySettlementHeader header = new DailySettlementHeader("0510", DATE,
				Integer.toString(records.length), "00000", "0000");
		when(settlement.queryDay(DATE)).thenReturn(new SettlementResult(header, List.of(records)));
	}

	private ReconType typeOf(ReconciliationReport r, String tranId) {
		return r.entries().stream().filter(e -> e.tranId().equals(tranId))
				.map(ReconEntry::type).findFirst().orElseThrow();
	}

	@Test
	@DisplayName("네 유형이 한 번에 갈린다 — 일치 / 금액상이 / 우리만있음 / 저쪽만있음")
	void classifiesFourTypes() {
		String a = "GWMNU20260709000000001"; // MATCH
		String b = "GWMNU20260709000000002"; // MISMATCH(금액상이)
		String c = "GWMNU20260709000000003"; // LEDGER_ONLY(계정계 없음인데 SUCCESS)
		String e = "GWMNU20260709000000004"; // CORE_ONLY(계정계만, 원장 누락)

		when(ledger.ofDay(DATE)).thenReturn(List.of(
				ledgerView(a, TransactionStatus.SUCCESS, 1000L),
				ledgerView(b, TransactionStatus.SUCCESS, 2000L),
				ledgerView(c, TransactionStatus.SUCCESS, 3000L)));
		coreReturns(coreRec(a, 1000, false), coreRec(b, 9999, false), coreRec(e, 5000, false));

		ReconciliationReport r = service.reconcile(DATE);

		assertThat(typeOf(r, a)).isEqualTo(ReconType.MATCH);
		assertThat(typeOf(r, b)).isEqualTo(ReconType.MISMATCH);
		assertThat(typeOf(r, c)).isEqualTo(ReconType.LEDGER_ONLY);
		assertThat(typeOf(r, e)).isEqualTo(ReconType.CORE_ONLY);
		assertThat(r.match()).isEqualTo(1);
		assertThat(r.mismatch()).isEqualTo(1);
		assertThat(r.ledgerOnly()).isEqualTo(1);
		assertThat(r.coreOnly()).isEqualTo(1);
		verify(resolution, never()).resolve(any());
	}

	@Test
	@DisplayName("UNKNOWN은 대조 전에 자동 해소된다 — 처리됨→망취소(CANCELED), 미처리→FAILED")
	void autoResolvesUnknownBeforeClassifying() {
		String u1 = "GWMNU20260709000000011"; // 계정계 처리함 → 망취소 → CANCELED
		String u2 = "GWMNU20260709000000012"; // 계정계 미처리 → FAILED

		when(resolution.resolve(u1)).thenReturn(
				new ResolutionOutcome(u1, true, Resolution.NET_CANCELED, null, null));
		when(resolution.resolve(u2)).thenReturn(
				new ResolutionOutcome(u2, false, Resolution.CONFIRMED_UNPROCESSED, null, null));

		// 1차 ofDay: 둘 다 UNKNOWN. 2차 ofDay(해소 후): u1=CANCELED, u2=FAILED.
		when(ledger.ofDay(DATE)).thenReturn(
				List.of(ledgerView(u1, TransactionStatus.UNKNOWN, 1000L),
						ledgerView(u2, TransactionStatus.UNKNOWN, null)),
				List.of(ledgerView(u1, TransactionStatus.CANCELED, 1000L),
						ledgerView(u2, TransactionStatus.FAILED, null)));
		coreReturns(coreRec(u1, 1000, true)); // 계정계는 u1을 취소됨으로, u2는 기록 없음.

		ReconciliationReport r = service.reconcile(DATE);

		assertThat(r.unknownResolved()).isEqualTo(2);
		verify(ledger).resolve(eq(u1), eq(TransactionStatus.CANCELED), eq("NET_CANCEL"), any());
		verify(ledger).resolve(eq(u2), eq(TransactionStatus.FAILED), eq("STATUS_INQUIRY"), any());
		// 해소 후: u1 취소=취소(일치) MATCH, u2 미처리=계정계 없음 MATCH.
		assertThat(typeOf(r, u1)).isEqualTo(ReconType.MATCH);
		assertThat(typeOf(r, u2)).isEqualTo(ReconType.MATCH);
	}

	@Test
	@DisplayName("해소 전문이 불통이면 UNKNOWN을 남기고, 계정계엔 있으니 저쪽만있음으로 분류")
	void unresolvedUnknownStaysCoreOnly() {
		String u = "GWMNU20260709000000021";
		when(resolution.resolve(u)).thenThrow(new GatewayException("계정계 불통", new RuntimeException()));
		when(ledger.ofDay(DATE)).thenReturn(List.of(ledgerView(u, TransactionStatus.UNKNOWN, 1000L)));
		coreReturns(coreRec(u, 1000, false));

		ReconciliationReport r = service.reconcile(DATE);

		assertThat(r.unknownResolved()).isEqualTo(0);
		assertThat(typeOf(r, u)).isEqualTo(ReconType.CORE_ONLY);
		verify(ledger, never()).resolve(any(), any(), any(), any());
	}

	@Test
	@DisplayName("리포트 요약이 원장(reconciliation_run)에 저장된다")
	void savesRunSummary() {
		when(ledger.ofDay(DATE)).thenReturn(List.of());
		coreReturns();

		ReconciliationReport r = service.reconcile(DATE);

		Map<String, Integer> counts = ReconciliationService.counts(r);
		assertThat(counts).containsEntry("MATCH", 0).containsKey("UNKNOWN_RESOLVED");
		verify(reconLog).save(eq(DATE), eq(0), eq(0), eq(0), eq(0), eq(0), eq(0), eq(0));
	}
}
