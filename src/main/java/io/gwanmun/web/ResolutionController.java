package io.gwanmun.web;

import io.gwanmun.gateway.GatewayException;
import io.gwanmun.gateway.TransactionResolutionService;
import io.gwanmun.gateway.TransactionResolutionService.Leg;
import io.gwanmun.gateway.TransactionResolutionService.ResolutionOutcome;
import io.gwanmun.ledger.TransactionLedger;
import io.gwanmun.ledger.TransactionLedger.LedgerView;
import io.gwanmun.ledger.TransactionStatus;
import io.gwanmun.message.HexFormat2;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * UNKNOWN 해소 엔드포인트 (Phase 6). 원장에 UNKNOWN으로 적힌 거래를 상태조회 → (처리됐으면)
 * 망취소의 두 다리로 확정 짓고, 원장에 해소 이력(시각·방법)을 남긴다.
 *
 * <ul>
 *   <li>POST /api/gateway/resolve/{tranId} — UNKNOWN 거래 하나를 해소.
 *       관문 필터({@code /api/gateway/**}) 안이므로 API 키가 필요하다 — 해소는 원장 상태를 바꾸는
 *       운영 행위라 아무나 못 누르게 한다.</li>
 * </ul>
 *
 * <p>해소 플로우의 판정({@code TransactionResolutionService.Resolution})과 원장 상태의 매핑은
 * 조립층인 여기서 한다 — gateway 모듈은 ledger를 모른다(모듈 경계).
 */
@RestController
@RequestMapping("/api/gateway")
public class ResolutionController {

	private final TransactionResolutionService resolution;
	private final TransactionLedger ledger;

	public ResolutionController(TransactionResolutionService resolution, TransactionLedger ledger) {
		this.resolution = resolution;
		this.ledger = ledger;
	}

	@PostMapping("/resolve/{tranId}")
	public ResponseEntity<?> resolve(@PathVariable String tranId) {
		Optional<LedgerView> target = ledger.find(tranId);
		if (target.isEmpty()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Map.of("error", "원장에 없는 거래ID: " + tranId));
		}
		if (target.get().status() != TransactionStatus.UNKNOWN) {
			// 해소는 "모르는" 거래에만 의미가 있다 — 이미 확정된 거래는 건드리지 않는다.
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
					"error", "UNKNOWN 거래만 해소할 수 있습니다.",
					"tranId", tranId,
					"currentStatus", target.get().status().name()));
		}

		ResolutionOutcome outcome;
		try {
			outcome = resolution.resolve(tranId);
		} catch (GatewayException e) {
			// 해소 전문 왕복 자체가 실패 — 원장은 UNKNOWN 그대로다(해소는 멱등이라 다시 시도하면 된다).
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
					"error", e.getMessage(),
					"tranId", tranId,
					"ledgerStatus", TransactionStatus.UNKNOWN.name()));
		}

		LedgerView after = switch (outcome.resolution()) {
			case CONFIRMED_UNPROCESSED -> ledger.resolve(tranId, TransactionStatus.FAILED,
					"STATUS_INQUIRY", "상태조회 결과 미처리 — FAILED 확정");
			case NET_CANCELED -> ledger.resolve(tranId, TransactionStatus.CANCELED,
					"NET_CANCEL", "상태조회 처리됨 → 망취소 성공 — CANCELED 확정");
			case CANCEL_REJECTED -> null; // 상태 불일치 — UNKNOWN 유지(정직하게 미해소).
		};

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("tranId", tranId);
		body.put("before", TransactionStatus.UNKNOWN.name());
		body.put("processedAtCore", outcome.processedAtCore());
		body.put("statusInquiry", legView(outcome.statusInquiry()));
		body.put("netCancel", outcome.netCancel() == null ? null : legView(outcome.netCancel()));
		body.put("resolution", outcome.resolution().name());
		body.put("after", after == null ? TransactionStatus.UNKNOWN.name() : after.status().name());
		body.put("resolvedAt", after == null ? null : after.resolvedAt());
		body.put("resolutionMethod", after == null ? null : after.resolutionMethod());
		return ResponseEntity.ok(body);
	}

	/** 전문 한 다리를 hex로 — 상태조회·망취소가 실제 전문 왕복임이 화면에 드러나게. */
	private static Map<String, Object> legView(Leg leg) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("requestHex", HexFormat2.toHex(leg.requestFrame()));
		m.put("requestLength", leg.requestFrame().length);
		m.put("responseHex", HexFormat2.toHex(leg.responseFrame()));
		m.put("responseLength", leg.responseFrame().length);
		m.put("coreMessage", leg.coreMessage());
		return m;
	}
}
