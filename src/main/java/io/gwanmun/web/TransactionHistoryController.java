package io.gwanmun.web;

import io.gwanmun.core.ConnectionPool;
import io.gwanmun.core.CoreBankingClient;
import io.gwanmun.core.TransactionHistoryClient;
import io.gwanmun.core.TransactionHistoryClient.HistoryClientException;
import io.gwanmun.core.TransactionHistoryClient.HistoryResult;
import io.gwanmun.ledger.TransactionIdGenerator;
import io.gwanmun.ledger.TransactionLedger;
import io.gwanmun.ledger.TransactionLedger.LedgerRecord;
import io.gwanmun.ledger.TransactionStatus;
import io.gwanmun.message.HexFormat2;
import io.gwanmun.message.dto.TransactionHistoryHeader;
import io.gwanmun.message.dto.TransactionRecord;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 가변 전문(거래내역 조회) 왕복 + 커넥션 풀 상태 엔드포인트.
 *
 * <ul>
 *   <li>POST /api/history — {"accountNo":"...","count":N} → 거래ID + 요청/응답 전선 hex(길이 헤더
 *       포함) + 파싱된 헤더 + 레코드 N건 + 이번 왕복의 소켓 재사용 횟수 + 풀 상태</li>
 *   <li>GET  /api/pool/stats — 두 풀(잔액조회·거래내역)의 현재 상태</li>
 * </ul>
 *
 * <p>이 경로는 관문 필터({@code /api/gateway/*})의 유량제어 밖이다 — 풀 재사용을 보려면 짧은 시간에
 * 여러 번 왕복해야 하는데, Phase 3의 분당 한도(용량 5)에 걸리면 그걸 못 보기 때문이다. Phase 4의
 * 초점은 전송 계층(프레이밍·풀)이라, 통역 계열 엔드포인트(/api/build 등)와 같은 무검문 경로에 둔다.
 *
 * <p><b>Phase 5</b>: 이 거래도 원장에 적는다 — 거래고유번호 채번 + 3값 상태 비동기 적재.
 */
@RestController
@RequestMapping("/api")
public class TransactionHistoryController {

	private static final Pattern ACCOUNT_NO = Pattern.compile("\\d{1,14}");

	/** 거래내역 조회의 게이트웨이측 거래코드(원장 분류용 — 요청 전문에는 별도 거래코드 필드가 없다). */
	private static final String TX_CODE_HISTORY = "HI01";

	private final TransactionHistoryClient historyClient;
	private final CoreBankingClient balanceClient;
	private final TransactionLedger ledger;
	private final TransactionIdGenerator txIds;
	private final Timer roundtripTimer;

	public TransactionHistoryController(TransactionHistoryClient historyClient,
			CoreBankingClient balanceClient, TransactionLedger ledger,
			TransactionIdGenerator txIds, MeterRegistry meterRegistry) {
		this.historyClient = historyClient;
		this.balanceClient = balanceClient;
		this.ledger = ledger;
		this.txIds = txIds;
		this.roundtripTimer = Timer.builder("gwanmun.core.roundtrip")
				.description("계정계 TCP 소켓 왕복 소요 시간")
				.tag("tx", "history")
				.register(meterRegistry);
	}

	@PostMapping("/history")
	public ResponseEntity<?> history(@RequestBody HistoryRequest req) {
		String transactionId = txIds.next();
		String accountNo = req.accountNo() == null ? "" : req.accountNo().trim();
		Instant requestedAt = Instant.now();

		if (!ACCOUNT_NO.matcher(accountNo).matches()) {
			ledger.record(new LedgerRecord(transactionId, TX_CODE_HISTORY, accountNo,
					TransactionStatus.FAILED, null, "입력 오류: accountNo 형식 위반",
					requestedAt, 0, correlationId()));
			throw new IllegalArgumentException("accountNo 는 숫자 1~14자리여야 합니다. (입력='" + accountNo + "')");
		}
		int count = req.count() == null ? 5 : Math.max(1, Math.min(20, req.count()));

		long startNanos = System.nanoTime();
		HistoryResult r;
		try {
			r = historyClient.query(accountNo, count);
		} catch (HistoryClientException e) {
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
			TransactionStatus status = TransactionStatus.ofFailure(e);
			ledger.record(new LedgerRecord(transactionId, TX_CODE_HISTORY, accountNo,
					status, null, e.getMessage(), requestedAt, elapsedMs, correlationId()));
			HttpStatus http = status == TransactionStatus.UNKNOWN
					? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("error", e.getMessage());
			body.put("transactionId", transactionId);
			body.put("ledgerStatus", status.name());
			return ResponseEntity.status(http).body(body);
		}
		roundtripTimer.record(r.elapsedMs(), TimeUnit.MILLISECONDS);

		TransactionStatus status = TransactionStatus.ofResponseCode(r.header().getResponseCode());
		ledger.record(new LedgerRecord(transactionId, TX_CODE_HISTORY, accountNo,
				status, r.header().getResponseCode(), null,
				requestedAt, r.elapsedMs(), correlationId()));

		return ResponseEntity.ok(new HistoryRoundTrip(
				transactionId, status.name(),
				HexFormat2.toHex(r.requestWire()), HexFormat2.toAscii(r.requestWire()), r.requestWire().length,
				HexFormat2.toHex(r.responseWire()), HexFormat2.toAscii(r.responseWire()), r.responseWire().length,
				r.header(), r.records(), r.records().size(),
				r.coreHost() + ":" + r.corePort(), r.elapsedMs(), r.reuseCount(),
				historyClient.poolStats()));
	}

	@GetMapping("/pool/stats")
	public Map<String, ConnectionPool.Stats> poolStats() {
		return Map.of(
				"txnHistory", historyClient.poolStats(),
				"balance", balanceClient.poolStats());
	}

	private static String correlationId() {
		return MDC.get(CorrelationIdFilter.MDC_KEY);
	}

	// --- 요청/응답 바디 ---

	public record HistoryRequest(String accountNo, Integer count) {
	}

	/**
	 * @param transactionId 이 거래에 채번된 거래고유번호(원장·로그와 같은 값)
	 * @param ledgerStatus  원장에 적힌 3값 상태
	 * @param requestHex    소켓으로 나간 요청 전선 바이트(4byte 길이 헤더 + 40byte 본문) hex
	 * @param responseHex   소켓으로 돌아온 응답 전선 바이트(4byte 길이 헤더 + 가변 본문) hex
	 * @param header        응답 본문의 고정 헤더(건수·전체길이 포함)
	 * @param records       가변으로 붙어 온 거래 레코드
	 * @param reuseCount    이번 왕복이 쓴 소켓의 재사용 횟수(0=갓 연 소켓, 1↑=재사용)
	 * @param pool          왕복 직후의 거래내역 풀 상태
	 */
	public record HistoryRoundTrip(
			String transactionId, String ledgerStatus,
			String requestHex, String requestAscii, int requestLength,
			String responseHex, String responseAscii, int responseLength,
			TransactionHistoryHeader header, List<TransactionRecord> records, int recordCount,
			String core, long elapsedMs, int reuseCount, ConnectionPool.Stats pool) {
	}

	// --- 예외 매핑 ---

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", e.getMessage() == null ? "잘못된 요청" : e.getMessage()));
	}
}
