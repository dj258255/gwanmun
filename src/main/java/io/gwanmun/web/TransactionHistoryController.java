package io.gwanmun.web;

import io.gwanmun.core.ConnectionPool;
import io.gwanmun.core.CoreBankingClient;
import io.gwanmun.core.TransactionHistoryClient;
import io.gwanmun.core.TransactionHistoryClient.HistoryClientException;
import io.gwanmun.core.TransactionHistoryClient.HistoryResult;
import io.gwanmun.message.HexFormat2;
import io.gwanmun.message.dto.TransactionHistoryHeader;
import io.gwanmun.message.dto.TransactionRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 가변 전문(거래내역 조회) 왕복 + 커넥션 풀 상태 엔드포인트.
 *
 * <ul>
 *   <li>POST /api/history — {"accountNo":"...","count":N} → 요청/응답 전선 hex(길이 헤더 포함) +
 *       파싱된 헤더 + 레코드 N건 + 이번 왕복의 소켓 재사용 횟수 + 풀 상태</li>
 *   <li>GET  /api/pool/stats — 두 풀(잔액조회·거래내역)의 현재 상태</li>
 * </ul>
 *
 * <p>이 경로는 관문 필터({@code /api/gateway/*})의 유량제어 밖이다 — 풀 재사용을 보려면 짧은 시간에
 * 여러 번 왕복해야 하는데, Phase 3의 분당 한도(용량 5)에 걸리면 그걸 못 보기 때문이다. Phase 4의
 * 초점은 전송 계층(프레이밍·풀)이라, 통역 계열 엔드포인트(/api/build 등)와 같은 무검문 경로에 둔다.
 */
@RestController
@RequestMapping("/api")
public class TransactionHistoryController {

	private static final Pattern ACCOUNT_NO = Pattern.compile("\\d{1,14}");

	private final TransactionHistoryClient historyClient;
	private final CoreBankingClient balanceClient;

	public TransactionHistoryController(TransactionHistoryClient historyClient, CoreBankingClient balanceClient) {
		this.historyClient = historyClient;
		this.balanceClient = balanceClient;
	}

	@PostMapping("/history")
	public HistoryRoundTrip history(@RequestBody HistoryRequest req) {
		String accountNo = req.accountNo() == null ? "" : req.accountNo().trim();
		if (!ACCOUNT_NO.matcher(accountNo).matches()) {
			throw new IllegalArgumentException("accountNo 는 숫자 1~14자리여야 합니다. (입력='" + accountNo + "')");
		}
		int count = req.count() == null ? 5 : Math.max(1, Math.min(20, req.count()));

		HistoryResult r = historyClient.query(accountNo, count);
		return new HistoryRoundTrip(
				HexFormat2.toHex(r.requestWire()), HexFormat2.toAscii(r.requestWire()), r.requestWire().length,
				HexFormat2.toHex(r.responseWire()), HexFormat2.toAscii(r.responseWire()), r.responseWire().length,
				r.header(), r.records(), r.records().size(),
				r.coreHost() + ":" + r.corePort(), r.elapsedMs(), r.reuseCount(),
				historyClient.poolStats());
	}

	@GetMapping("/pool/stats")
	public Map<String, ConnectionPool.Stats> poolStats() {
		return Map.of(
				"txnHistory", historyClient.poolStats(),
				"balance", balanceClient.poolStats());
	}

	// --- 요청/응답 바디 ---

	public record HistoryRequest(String accountNo, Integer count) {
	}

	/**
	 * @param requestHex   소켓으로 나간 요청 전선 바이트(4byte 길이 헤더 + 40byte 본문) hex
	 * @param responseHex  소켓으로 돌아온 응답 전선 바이트(4byte 길이 헤더 + 가변 본문) hex
	 * @param header       응답 본문의 고정 헤더(건수·전체길이 포함)
	 * @param records      가변으로 붙어 온 거래 레코드
	 * @param reuseCount   이번 왕복이 쓴 소켓의 재사용 횟수(0=갓 연 소켓, 1↑=재사용)
	 * @param pool         왕복 직후의 거래내역 풀 상태
	 */
	public record HistoryRoundTrip(
			String requestHex, String requestAscii, int requestLength,
			String responseHex, String responseAscii, int responseLength,
			TransactionHistoryHeader header, List<TransactionRecord> records, int recordCount,
			String core, long elapsedMs, int reuseCount, ConnectionPool.Stats pool) {
	}

	// --- 예외 매핑 ---

	@ExceptionHandler(HistoryClientException.class)
	public ResponseEntity<Map<String, String>> handleHistory(HistoryClientException e) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(Map.of("error", e.getMessage()));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", e.getMessage() == null ? "잘못된 요청" : e.getMessage()));
	}
}
