package io.gwanmun.web;

import io.gwanmun.core.SettlementClient;
import io.gwanmun.core.SettlementClient.SettlementResult;
import io.gwanmun.gateway.GatewayException;
import io.gwanmun.gateway.TransactionResolutionService;
import io.gwanmun.gateway.TransactionResolutionService.ResolutionOutcome;
import io.gwanmun.ledger.ReconciliationLog;
import io.gwanmun.ledger.TransactionLedger;
import io.gwanmun.ledger.TransactionLedger.LedgerView;
import io.gwanmun.ledger.TransactionStatus;
import io.gwanmun.message.dto.DailySettlementRecord;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * EOD 대사 배치(Phase 9). 게이트웨이 <b>원장</b>과 계정계 <b>당일 처리내역</b>을 거래고유번호로
 * 전량 대조해, 불일치를 네 유형으로 가른다. 원장이 "검증되지 않은 진실"이던 문제 —
 * 특히 취소·미확인 건 — 을 매일의 대조로 검증된 진실로 만든다.
 *
 * <p>순서:
 * <ol>
 *   <li><b>UNKNOWN 자동 해소</b>: 원장의 그 날 UNKNOWN 거래를 Phase 6 해소 절차(상태조회 →
 *       처리됐으면 망취소)로 먼저 확정 짓는다. 대사의 신뢰도는 미확인 건을 얼마나 줄이느냐로 갈린다.</li>
 *   <li><b>4유형 분류</b>: (해소 반영 후) 원장 vs 계정계를 대조한다.
 *     <ul>
 *       <li>MATCH — 양쪽있음 + 금액·상태 일치(정상)</li>
 *       <li>MISMATCH — 양쪽있음 + 금액/상태 상이</li>
 *       <li>LEDGER_ONLY — 우리만있음(계정계 미처리인데 원장 SUCCESS)</li>
 *       <li>CORE_ONLY — 저쪽만있음(계정계 처리했는데 원장 UNKNOWN/누락)</li>
 *     </ul></li>
 *   <li>요약을 원장(reconciliation_run)·로그에 남기고 화면에 표로 돌려준다.</li>
 * </ol>
 *
 * <p>이 서비스는 계정계(core)·원장(ledger)·해소(gateway)를 한자리에서 조립한다 — 세 모듈을 아는 곳은
 * 조립층(web)뿐이라 여기에 둔다(모듈 경계).
 */
@Service
public class ReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

	private final SettlementClient settlementClient;
	private final TransactionLedger ledger;
	private final TransactionResolutionService resolution;
	private final ReconciliationLog reconLog;
	private final MeterRegistry meterRegistry;

	public ReconciliationService(SettlementClient settlementClient, TransactionLedger ledger,
			TransactionResolutionService resolution, ReconciliationLog reconLog,
			MeterRegistry meterRegistry) {
		this.settlementClient = settlementClient;
		this.ledger = ledger;
		this.resolution = resolution;
		this.reconLog = reconLog;
		this.meterRegistry = meterRegistry;
	}

	/** 불일치 4유형. */
	public enum ReconType {
		MATCH, MISMATCH, LEDGER_ONLY, CORE_ONLY
	}

	/** 대사 한 줄 — 한 거래(또는 계정계 단독 레코드)의 대조 결과. */
	public record ReconEntry(String tranId, ReconType type, String ledgerStatus, Long ledgerAmount,
			Boolean coreProcessed, Long coreAmount, Boolean coreCanceled, String note) {
	}

	/** 대사 1회 리포트(요약 + 상세). */
	public record ReconciliationReport(String settleDate, Instant ranAt,
			int match, int mismatch, int ledgerOnly, int coreOnly, int unknownResolved,
			int ledgerTotal, int coreTotal, List<ReconEntry> entries) {
	}

	/** 그 날짜(YYYYMMDD)의 EOD 대사를 실행한다. */
	public ReconciliationReport reconcile(String yyyymmdd) {
		log.info("EOD 대사 시작: 기준일={}", yyyymmdd);

		// 1) UNKNOWN 자동 해소 — 대조 전에 미확인부터 줄인다. 해소의 망취소는 계정계 기록도
		//    바꾸므로(취소 반영), 계정계 스냅샷은 해소 "이후"에 떠야 양쪽이 같은 시점을 본다.
		Map<String, String> resolvedNote = new HashMap<>();
		int unknownResolved = 0;
		for (LedgerView e : ledger.ofDay(yyyymmdd)) {
			if (e.status() != TransactionStatus.UNKNOWN) {
				continue;
			}
			String note = tryResolve(e.transactionId());
			if (note != null) {
				resolvedNote.put(e.transactionId(), note);
				unknownResolved++;
			}
		}

		// 2) (해소 반영 후) 계정계측 당일 처리내역을 뜨고 4유형으로 분류한다.
		SettlementResult acc = settlementClient.queryDay(yyyymmdd);
		Map<String, DailySettlementRecord> coreByTran = new HashMap<>();
		for (DailySettlementRecord r : acc.records()) {
			coreByTran.put(r.getTranId(), r);
		}
		List<LedgerView> day = ledger.ofDay(yyyymmdd);
		Set<String> seenCore = new HashSet<>();
		List<ReconEntry> entries = new ArrayList<>();
		for (LedgerView e : day) {
			DailySettlementRecord a = coreByTran.get(e.transactionId());
			if (a != null) {
				seenCore.add(e.transactionId());
			}
			entries.add(classify(e, a, resolvedNote.get(e.transactionId())));
		}
		// 계정계엔 있는데 원장엔 아예 없는 거래(누락) → 저쪽만있음.
		for (DailySettlementRecord a : acc.records()) {
			if (!seenCore.contains(a.getTranId())) {
				entries.add(new ReconEntry(a.getTranId(), ReconType.CORE_ONLY, "(없음)", null,
						true, parseAmount(a.getAmount()), DailySettlementRecord.CANCELED.equals(a.getStatusFlag()),
						"계정계 처리 기록만 있고 원장에 없음(누락)"));
			}
		}

		int match = count(entries, ReconType.MATCH);
		int mismatch = count(entries, ReconType.MISMATCH);
		int ledgerOnly = count(entries, ReconType.LEDGER_ONLY);
		int coreOnly = count(entries, ReconType.CORE_ONLY);

		meterRegistry.counter("gwanmun.reconciliation.runs").increment();
		meterRegistry.counter("gwanmun.reconciliation.mismatches", "type", "MISMATCH").increment(mismatch);
		meterRegistry.counter("gwanmun.reconciliation.mismatches", "type", "LEDGER_ONLY").increment(ledgerOnly);
		meterRegistry.counter("gwanmun.reconciliation.mismatches", "type", "CORE_ONLY").increment(coreOnly);

		reconLog.save(yyyymmdd, match, mismatch, ledgerOnly, coreOnly, unknownResolved,
				day.size(), acc.records().size());
		log.info("EOD 대사 완료: 기준일={} 일치={} 상이={} 우리만={} 저쪽만={} UNKNOWN해소={} (원장 {}건, 계정계 {}건)",
				yyyymmdd, match, mismatch, ledgerOnly, coreOnly, unknownResolved, day.size(), acc.records().size());

		return new ReconciliationReport(yyyymmdd, Instant.now(), match, mismatch, ledgerOnly, coreOnly,
				unknownResolved, day.size(), acc.records().size(), entries);
	}

	/**
	 * UNKNOWN 하나를 해소 시도한다. 성공하면 원장을 CANCELED/FAILED로 확정하고 노트를 돌려준다.
	 * 해소 전문 왕복 자체가 실패하면(계정계 불통) UNKNOWN을 그대로 두고 null.
	 */
	private String tryResolve(String tranId) {
		try {
			ResolutionOutcome o = resolution.resolve(tranId);
			return switch (o.resolution()) {
				case CONFIRMED_UNPROCESSED -> {
					ledger.resolve(tranId, TransactionStatus.FAILED, "STATUS_INQUIRY",
							"대사 자동 해소: 상태조회 미처리 → FAILED 확정");
					yield "대사 자동 해소: UNKNOWN → FAILED(계정계 미처리)";
				}
				case NET_CANCELED -> {
					ledger.resolve(tranId, TransactionStatus.CANCELED, "NET_CANCEL",
							"대사 자동 해소: 상태조회 처리됨 → 망취소 성공 → CANCELED 확정");
					yield "대사 자동 해소: UNKNOWN → CANCELED(망취소)";
				}
				case CANCEL_REJECTED -> null; // 상태 불일치 — UNKNOWN 유지(정직하게 미해소).
			};
		} catch (GatewayException e) {
			log.warn("대사 자동 해소 실패(UNKNOWN 유지): tranId={} 원인={}", tranId, e.getMessage());
			return null;
		}
	}

	private ReconEntry classify(LedgerView e, DailySettlementRecord core, String resolvedNote) {
		String status = e.status().name();
		Long ledgerAmount = e.amount();
		String base = resolvedNote == null ? "" : resolvedNote + " · ";

		if (core == null) {
			// 계정계 기록 없음.
			if (e.status() == TransactionStatus.SUCCESS || e.status() == TransactionStatus.CANCELED) {
				return new ReconEntry(e.transactionId(), ReconType.LEDGER_ONLY, status, ledgerAmount,
						false, null, null, base + "계정계에 처리 기록 없음(원장은 " + status + ")");
			}
			// FAILED/UNKNOWN(미해소) — 양쪽 다 처리 안 됨으로 수렴(일치).
			return new ReconEntry(e.transactionId(), ReconType.MATCH, status, ledgerAmount,
					false, null, null, base + "양쪽 모두 미처리(일치)");
		}

		// 계정계 기록 있음.
		Long coreAmount = parseAmount(core.getAmount());
		boolean coreCanceled = DailySettlementRecord.CANCELED.equals(core.getStatusFlag());

		if (e.status() == TransactionStatus.UNKNOWN) {
			return new ReconEntry(e.transactionId(), ReconType.CORE_ONLY, status, ledgerAmount,
					true, coreAmount, coreCanceled, base + "계정계는 처리, 원장은 UNKNOWN(미확정)");
		}
		if (e.status() == TransactionStatus.FAILED) {
			return new ReconEntry(e.transactionId(), ReconType.MISMATCH, status, ledgerAmount,
					true, coreAmount, coreCanceled, base + "원장 FAILED인데 계정계는 처리함");
		}

		boolean ledgerCanceled = e.status() == TransactionStatus.CANCELED;
		if (ledgerCanceled || coreCanceled) {
			// 취소 여부가 먼저다 — 무효화된 거래는 금액 대조가 무의미하다. 취소 상태가 서로 다르면 불일치.
			if (ledgerCanceled == coreCanceled) {
				return new ReconEntry(e.transactionId(), ReconType.MATCH, status, ledgerAmount,
						true, coreAmount, coreCanceled, base + "양쪽 취소 일치");
			}
			return new ReconEntry(e.transactionId(), ReconType.MISMATCH, status, ledgerAmount,
					true, coreAmount, coreCanceled, base + "상태 상이(원장 "
							+ (ledgerCanceled ? "취소" : "정상") + " vs 계정계 " + (coreCanceled ? "취소" : "정상") + ")");
		}
		// 양쪽 정상 — 금액을 대조한다.
		boolean amountMatch = ledgerAmount != null && ledgerAmount.equals(coreAmount);
		if (amountMatch) {
			return new ReconEntry(e.transactionId(), ReconType.MATCH, status, ledgerAmount,
					true, coreAmount, coreCanceled, base + "양쪽있음 · 금액 일치");
		}
		return new ReconEntry(e.transactionId(), ReconType.MISMATCH, status, ledgerAmount,
				true, coreAmount, coreCanceled,
				base + "금액 상이(원장 " + ledgerAmount + " vs 계정계 " + coreAmount + ")");
	}

	private static int count(List<ReconEntry> entries, ReconType type) {
		return (int) entries.stream().filter(e -> e.type() == type).count();
	}

	private static Long parseAmount(String s) {
		try {
			return s == null || s.isBlank() ? null : Long.parseLong(s.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 상태 요약(화면 그리드용) — 유형별 건수 맵. */
	public static Map<String, Integer> counts(ReconciliationReport r) {
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put("MATCH", r.match());
		m.put("MISMATCH", r.mismatch());
		m.put("LEDGER_ONLY", r.ledgerOnly());
		m.put("CORE_ONLY", r.coreOnly());
		m.put("UNKNOWN_RESOLVED", r.unknownResolved());
		return m;
	}
}
