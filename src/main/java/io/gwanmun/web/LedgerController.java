package io.gwanmun.web;

import io.gwanmun.ledger.TransactionLedger;
import io.gwanmun.ledger.TransactionLedger.LedgerView;
import io.gwanmun.ledger.TransactionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 거래 원장 조회 엔드포인트(화면용). 계좌는 이미 마스킹된 값만 나간다.
 *
 * <ul>
 *   <li>GET /api/ledger/recent?limit=N — 최근 거래 N건(최신 먼저)</li>
 *   <li>GET /api/ledger/summary — 상태별 누적 건수(SUCCESS/FAILED/UNKNOWN/CANCELED)</li>
 *   <li>GET /api/ledger/unknown?limit=N — 해소 대상(UNKNOWN) 거래 목록(Phase 6)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ledger")
public class LedgerController {

	private final TransactionLedger ledger;

	public LedgerController(TransactionLedger ledger) {
		this.ledger = ledger;
	}

	@GetMapping("/recent")
	public List<LedgerView> recent(@RequestParam(defaultValue = "20") int limit) {
		return ledger.recent(limit);
	}

	/** 해소 대상 — UNKNOWN으로 남아 있는 거래 목록(Phase 6). */
	@GetMapping("/unknown")
	public List<LedgerView> unknown(@RequestParam(defaultValue = "20") int limit) {
		return ledger.byStatus(TransactionStatus.UNKNOWN, limit);
	}

	@GetMapping("/summary")
	public Map<String, Long> summary() {
		Map<TransactionStatus, Long> counts = ledger.summary();
		Map<String, Long> body = new LinkedHashMap<>();
		long total = 0;
		for (TransactionStatus status : TransactionStatus.values()) {
			long n = counts.getOrDefault(status, 0L);
			body.put(status.name(), n);
			total += n;
		}
		body.put("TOTAL", total);
		return body;
	}
}
