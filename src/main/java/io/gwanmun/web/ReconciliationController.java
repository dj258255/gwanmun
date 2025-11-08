package io.gwanmun.web;

import io.gwanmun.core.SettlementClient.SettlementException;
import io.gwanmun.ledger.ReconciliationLog;
import io.gwanmun.ledger.ReconciliationLog.RunView;
import io.gwanmun.web.ReconciliationService.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EOD 대사 엔드포인트(Phase 9, 화면·운영용).
 *
 * <ul>
 *   <li>POST /api/reconciliation/run?date=YYYYMMDD — 그 날짜의 대사를 즉시 실행(수동 트리거).
 *       date 생략 시 오늘. UNKNOWN 자동 해소 + 4유형 분류 리포트를 돌려준다.</li>
 *   <li>GET /api/reconciliation/runs?limit=N — 최근 대사 실행 요약(이력).</li>
 * </ul>
 *
 * <p><b>정직한 경계</b>: 이 경로는 관문 필터({@code /api/gateway/**}) 밖이다 — 관측 경로
 * (/api/ledger·/api/circuit)와 같은 판단이지만, 대사는 자동 해소(망취소)를 유발하는 <b>변경성 배치</b>다.
 * 실서비스라면 스케줄러 전용 또는 관리자 인증 뒤에 두어야 한다(확장 지점).
 */
@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationController.class);
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

	private final ReconciliationService reconciliation;
	private final ReconciliationLog reconLog;

	public ReconciliationController(ReconciliationService reconciliation, ReconciliationLog reconLog) {
		this.reconciliation = reconciliation;
		this.reconLog = reconLog;
	}

	@PostMapping("/run")
	public ResponseEntity<?> run(@RequestParam(required = false) String date) {
		String settleDate = (date == null || date.isBlank())
				? LocalDate.now().format(YYYYMMDD) : date.trim();
		try {
			ReconciliationReport report = reconciliation.reconcile(settleDate);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("settleDate", report.settleDate());
			body.put("ranAt", report.ranAt());
			body.put("counts", ReconciliationService.counts(report));
			body.put("ledgerTotal", report.ledgerTotal());
			body.put("coreTotal", report.coreTotal());
			body.put("entries", report.entries());
			return ResponseEntity.ok(body);
		} catch (SettlementException e) {
			// 계정계 당일 처리내역 조회 자체가 실패 — 대사를 못 돌린다(원장은 손대지 않았다).
			log.warn("대사 실패(계정계 당일 처리내역 조회 불통): 기준일={} 원인={}", settleDate, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
					"error", "계정계 당일 처리내역 조회에 실패해 대사를 실행하지 못했습니다.",
					"settleDate", settleDate));
		}
	}

	@GetMapping("/runs")
	public List<RunView> runs(@RequestParam(defaultValue = "20") int limit) {
		return reconLog.recent(limit);
	}
}
