package io.gwanmun.web;

import io.gwanmun.core.CircuitBreaker;
import io.gwanmun.core.CoreBankingClient;
import io.gwanmun.core.TransactionHistoryClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 서킷브레이커 상태 엔드포인트(화면용, Phase 6).
 *
 * <ul>
 *   <li>GET /api/circuit/stats — 두 백엔드(잔액조회·거래내역)의 서킷 상태·연속 실패·누적 거절/열림</li>
 * </ul>
 *
 * <p>이 경로는 관문 필터({@code /api/gateway/**}) 밖이다 — 서킷이 OPEN인 상황을 화면이 계속
 * 관찰해야 하는데, 관찰 요청이 유량제어에 막히면 그걸 못 보기 때문이다(/api/pool/stats와 같은 이유).
 */
@RestController
@RequestMapping("/api/circuit")
public class ResilienceController {

	private final CoreBankingClient balanceClient;
	private final TransactionHistoryClient historyClient;

	public ResilienceController(CoreBankingClient balanceClient, TransactionHistoryClient historyClient) {
		this.balanceClient = balanceClient;
		this.historyClient = historyClient;
	}

	@GetMapping("/stats")
	public Map<String, CircuitBreaker.Stats> stats() {
		Map<String, CircuitBreaker.Stats> body = new LinkedHashMap<>();
		body.put("coreBanking", balanceClient.circuitStats());
		body.put("txnHistory", historyClient.circuitStats());
		return body;
	}
}
