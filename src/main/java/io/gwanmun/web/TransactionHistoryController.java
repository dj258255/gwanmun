package io.gwanmun.web;

import io.gwanmun.core.ConnectionPool;
import io.gwanmun.core.CoreBankingClient;
import io.gwanmun.core.PoolExhaustedException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p><b>Phase 7(B2)</b>: {@code POST /api/history}를 관문 필터 체인(인증→라우팅→유량제어) 안으로
 * 편입했다. Phase 4 때 "풀 재사용 데모를 유량제어가 막는다"는 이유로 무검문 경로에 뒀지만, 이 경로는
 * <b>실제 계정계 거래를 유발</b>한다 — 데모 편의가 무인증 공짜 거래 통로로 남아 있던 셈이다. 데모는
 * API 키를 붙이면 그대로 돌고, 필요하면 {@code rate-capacity} 설정으로 여유를 준다. 반면
 * {@code GET /api/pool/stats}는 계정계 호출이 없는 읽기 전용 관측 경로라 관문 밖에 남긴다
 * (/api/ledger·/api/circuit/stats 와 같은 판단 — 장애 관찰 요청이 유량제어에 막히면 안 된다).
 *
 * <p><b>Phase 5</b>: 이 거래도 원장에 적는다 — 거래고유번호 채번 + 3값 상태 비동기 적재.
 */
@RestController
@RequestMapping("/api")
public class TransactionHistoryController {

	private static final Logger log = LoggerFactory.getLogger(TransactionHistoryController.class);

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
			// 원문 입력을 에코하지 않는다(B4) — 무엇이 틀렸는지는 규칙으로 설명한다.
			throw new IllegalArgumentException("accountNo 는 숫자 1~14자리여야 합니다.");
		}
		int count = req.count() == null ? 5 : Math.max(1, Math.min(20, req.count()));

		long startNanos = System.nanoTime();
		HistoryResult r;
		try {
			r = historyClient.query(accountNo, count);
		} catch (PoolExhaustedException e) {
			// 내부 커넥션 풀 고갈(Phase 7) — 계정계로 나가기 전의 명확한 실패: 원장 FAILED + 503.
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
			log.warn("풀 고갈로 거래 거절: txId={} ({})", transactionId, e.getMessage());
			ledger.record(new LedgerRecord(transactionId, TX_CODE_HISTORY, accountNo,
					TransactionStatus.FAILED, null, "게이트웨이 내부 커넥션 풀 고갈: " + e.getMessage(),
					requestedAt, elapsedMs, correlationId()));
			return errorBody(HttpStatus.SERVICE_UNAVAILABLE,
					"게이트웨이가 일시적으로 포화 상태입니다. 잠시 후 다시 시도하세요.",
					transactionId, TransactionStatus.FAILED);
		} catch (HistoryClientException e) {
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
			TransactionStatus status = TransactionStatus.ofFailure(e);
			// 상세(내부 host:port·예외 원문)는 서버 로그·원장까지만. 외부 응답은 일반화한다(B4).
			log.warn("거래내역 왕복 실패: txId={} status={} 원인={}", transactionId, status, e.getMessage());
			ledger.record(new LedgerRecord(transactionId, TX_CODE_HISTORY, accountNo,
					status, null, e.getMessage(), requestedAt, elapsedMs, correlationId()));
			HttpStatus http = status == TransactionStatus.UNKNOWN
					? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
			String reason = status == TransactionStatus.UNKNOWN
					? "계정계 응답을 확인하지 못했습니다(결과 미확인). 같은 요청을 재전송하지 마세요."
					: "계정계 처리에 실패했습니다.";
			return errorBody(http, reason, transactionId, status);
		} catch (RuntimeException e) {
			// 최후 방어(Phase 7) — 어떤 예외 경로로도 원장에 구멍을 내지 않는다.
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
			log.error("거래내역 미분류 오류: txId={}", transactionId, e);
			ledger.record(new LedgerRecord(transactionId, TX_CODE_HISTORY, accountNo,
					TransactionStatus.FAILED, null, "미분류 내부 오류: " + e,
					requestedAt, elapsedMs, correlationId()));
			return errorBody(HttpStatus.INTERNAL_SERVER_ERROR,
					"요청을 처리하지 못했습니다.", transactionId, TransactionStatus.FAILED);
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

	/**
	 * 실패 응답 공통 바디(B4). 외부에는 일반화한 사유·거래ID·correlation ID만 나간다 —
	 * 내부 host:port·예외 원문은 서버 로그와 원장에서 추적한다(correlationId가 그 열쇠).
	 */
	private static ResponseEntity<Map<String, Object>> errorBody(HttpStatus http, String reason,
			String transactionId, TransactionStatus status) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", reason);
		body.put("transactionId", transactionId);
		body.put("ledgerStatus", status.name());
		body.put("correlationId", correlationId());
		return ResponseEntity.status(http).body(body);
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
