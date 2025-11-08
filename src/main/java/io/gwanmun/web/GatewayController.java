package io.gwanmun.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gwanmun.core.CircuitOpenException;
import io.gwanmun.core.PoolExhaustedException;
import io.gwanmun.gateway.GatewayException;
import io.gwanmun.gateway.GatewayService;
import io.gwanmun.gateway.GatewayService.GatewayResult;
import io.gwanmun.ledger.IdempotencyService;
import io.gwanmun.ledger.IdempotencyService.Decision;
import io.gwanmun.ledger.TransactionIdGenerator;
import io.gwanmun.ledger.TransactionLedger;
import io.gwanmun.ledger.TransactionLedger.LedgerRecord;
import io.gwanmun.ledger.TransactionStatus;
import io.gwanmun.message.HexFormat2;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 게이트웨이 왕복 엔드포인트. REST(JSON)로 잔액조회를 받아 계정계와 전문으로 실제 소켓 왕복을 한 뒤,
 * 오간 요청/응답 전문 hex와 복원된 JSON을 함께 돌려준다(소켓을 타고 온 것임이 화면에 드러나게).
 *
 * <p><b>Phase 5</b>: 모든 거래에 거래고유번호를 채번하고, 결과를 3값 상태(SUCCESS/FAILED/UNKNOWN)로
 * 판정해 거래 원장에 비동기 적재한다. 특히 <b>타임아웃은 FAILED가 아니라 UNKNOWN</b>이다 — 응답을 못
 * 받았을 뿐, 계정계에서 처리됐을 수 있다(HTTP로는 504로 구분해 돌려준다).
 *
 * <ul>
 *   <li>POST /api/gateway/balance — {"accountNo":"..."} → 거래ID + 전문 hex + 파싱된 JSON</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

	private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

	/** 계좌번호는 숫자 1~14자리(요청 전문 계좌 필드 14byte). */
	private static final Pattern ACCOUNT_NO = Pattern.compile("\\d{1,14}");

	/** 잔액조회 거래코드(요청 전문의 txCode 필드와 동일한 값). */
	private static final String TX_CODE_BALANCE = "IN01";

	/** 이 엔드포인트의 경로 — 멱등키 스코프(키+메서드+경로)의 일부. */
	private static final String PATH = "/api/gateway/balance";

	private final GatewayService gateway;
	private final TransactionLedger ledger;
	private final TransactionIdGenerator txIds;
	private final IdempotencyService idempotency;
	private final ObjectMapper objectMapper;
	private final Duration idempotencyTtl;
	private final Timer roundtripTimer;

	public GatewayController(GatewayService gateway, TransactionLedger ledger,
			TransactionIdGenerator txIds, IdempotencyService idempotency, ObjectMapper objectMapper,
			@Value("${gwanmun.idempotency.ttl-seconds:86400}") long idempotencyTtlSeconds,
			MeterRegistry meterRegistry) {
		this.gateway = gateway;
		this.ledger = ledger;
		this.txIds = txIds;
		this.idempotency = idempotency;
		this.objectMapper = objectMapper;
		this.idempotencyTtl = Duration.ofSeconds(idempotencyTtlSeconds);
		// 커스텀 메트릭: 계정계 TCP 왕복 latency 타이머(거래 종류 태그).
		this.roundtripTimer = Timer.builder("gwanmun.core.roundtrip")
				.description("계정계 TCP 소켓 왕복 소요 시간")
				.tag("tx", "balance")
				.register(meterRegistry);
	}

	/**
	 * 잔액조회. {@code Idempotency-Key} 헤더가 있으면 재전송을 게이트웨이가 구분한다(Phase 9) —
	 * 처리 중 재요청은 409, 완료된 요청 재수신은 저장된 원응답을 <b>재실행 없이</b> 되돌려준다.
	 * 헤더가 없으면 Phase 8까지의 동작 그대로다.
	 */
	@PostMapping("/balance")
	public ResponseEntity<?> balance(@RequestBody BalanceRequest req,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			return doBalance(req, txIds.next()); // 멱등키 없음 — 기존 동작.
		}

		String accountNo = req.accountNo() == null ? "" : req.accountNo().trim();
		String fingerprint = IdempotencyService.fingerprint("POST " + PATH + " accountNo=" + accountNo);
		String transactionId = txIds.next();

		Decision d = idempotency.begin(idempotencyKey, "POST", PATH, fingerprint, transactionId, idempotencyTtl);
		switch (d.kind()) {
			case REPLAY -> {
				// 완료된 원응답을 그대로 — 재실행 없음(계정계 호출 0, 새 거래 0).
				return ResponseEntity.status(d.httpStatus())
						.header("X-Idempotent-Replay", "true")
						.contentType(MediaType.APPLICATION_JSON)
						.body(d.responseBody());
			}
			case IN_PROGRESS -> {
				return ResponseEntity.status(HttpStatus.CONFLICT)
						.body(Map.of("error", "같은 멱등키의 요청이 처리 중입니다. 잠시 후 결과를 조회하세요.",
								"idempotencyKey", idempotencyKey));
			}
			case PAYLOAD_MISMATCH -> {
				return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
						.body(Map.of("error", "같은 멱등키에 다른 요청 본문입니다. 멱등키를 재사용할 수 없습니다.",
								"idempotencyKey", idempotencyKey));
			}
			default -> { /* PROCEED — 아래에서 실제 처리 */ }
		}

		ResponseEntity<?> response = doBalance(req, transactionId);
		int status = response.getStatusCode().value();
		if (status == HttpStatus.SERVICE_UNAVAILABLE.value()) {
			// 내부 포화(풀 고갈·서킷 OPEN)의 503은 일시적 실패라 원응답으로 굳히지 않는다 —
			// 같은 멱등키로 재시도할 수 있게 선점을 놓아준다.
			idempotency.release(idempotencyKey, "POST", PATH);
		} else {
			idempotency.complete(idempotencyKey, "POST", PATH, status, serialize(response.getBody()));
		}
		return ResponseEntity.status(status)
				.header("X-Idempotent-Replay", "false")
				.contentType(MediaType.APPLICATION_JSON)
				.body(response.getBody());
	}

	/** 저장·재반환을 위해 응답 바디를 JSON 문자열로 굳힌다. */
	private String serialize(Object body) {
		try {
			return objectMapper.writeValueAsString(body);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("응답 직렬화 실패", e);
		}
	}

	private ResponseEntity<?> doBalance(BalanceRequest req, String transactionId) {
		String accountNo = req.accountNo() == null ? "" : req.accountNo().trim();
		Instant requestedAt = Instant.now();

		if (!ACCOUNT_NO.matcher(accountNo).matches()) {
			// 입력 오류 — 계정계로 나가기 전의 명확한 실패이므로 FAILED로 적는다.
			ledger.record(new LedgerRecord(transactionId, TX_CODE_BALANCE, accountNo,
					TransactionStatus.FAILED, null, "입력 오류: accountNo 형식 위반",
					requestedAt, 0, correlationId()));
			// 원문 입력을 에코하지 않는다(B4) — 무엇이 틀렸는지는 규칙으로 설명한다.
			throw new IllegalArgumentException("accountNo 는 숫자 1~14자리여야 합니다.");
		}

		long startNanos = System.nanoTime();
		GatewayResult r;
		try {
			r = gateway.balanceInquiry(accountNo, transactionId);
		} catch (PoolExhaustedException e) {
			// 내부 커넥션 풀 고갈(Phase 7) — 요청이 계정계로 나가기 전의 명확한 실패다.
			// (a) 원장에 FAILED로 남기고 (b) 서킷에는 계수되지 않았고(실행기가 보장) (c) 503으로 거절한다.
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
			log.warn("풀 고갈로 거래 거절: txId={} ({})", transactionId, e.getMessage());
			ledger.record(new LedgerRecord(transactionId, TX_CODE_BALANCE, accountNo,
					TransactionStatus.FAILED, null, "게이트웨이 내부 커넥션 풀 고갈: " + e.getMessage(),
					requestedAt, elapsedMs, correlationId()));
			return errorBody(HttpStatus.SERVICE_UNAVAILABLE,
					"게이트웨이가 일시적으로 포화 상태입니다. 잠시 후 다시 시도하세요.",
					transactionId, TransactionStatus.FAILED);
		} catch (GatewayException e) {
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
			// 응답을 못 받은 거래의 3값 판정 — 타임아웃/응답 없는 끊김은 UNKNOWN, 나머지는 FAILED.
			TransactionStatus status = TransactionStatus.ofFailure(e);
			// 상세(내부 host:port·예외 원문)는 서버 로그·원장까지만. 외부 응답은 일반화한다(B4).
			log.warn("게이트웨이 왕복 실패: txId={} status={} 원인={}", transactionId, status, e.getMessage());
			ledger.record(new LedgerRecord(transactionId, TX_CODE_BALANCE, accountNo,
					status, null, e.getMessage(), requestedAt, elapsedMs, correlationId()));
			HttpStatus http;
			String reason;
			if (status == TransactionStatus.UNKNOWN) {
				http = HttpStatus.GATEWAY_TIMEOUT;     // 504: 응답을 못 받았다(결과 미확인)
				reason = "계정계 응답을 확인하지 못했습니다(결과 미확인). 같은 요청을 재전송하지 마세요.";
			} else if (isCircuitOpen(e)) {
				http = HttpStatus.SERVICE_UNAVAILABLE; // 503: 서킷 OPEN — 계정계 호출 없이 즉시 거절(Phase 6)
				reason = "계정계 경로가 일시 차단 상태입니다. 잠시 후 다시 시도하세요.";
			} else {
				http = HttpStatus.BAD_GATEWAY;         // 502: 백엔드 실패
				reason = "계정계 처리에 실패했습니다.";
			}
			return errorBody(http, reason, transactionId, status);
		} catch (RuntimeException e) {
			// 최후 방어(Phase 7) — 어떤 예외 경로로도 원장에 구멍을 내지 않는다.
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
			log.error("게이트웨이 미분류 오류: txId={}", transactionId, e);
			ledger.record(new LedgerRecord(transactionId, TX_CODE_BALANCE, accountNo,
					TransactionStatus.FAILED, null, "미분류 내부 오류: " + e,
					requestedAt, elapsedMs, correlationId()));
			return errorBody(HttpStatus.INTERNAL_SERVER_ERROR,
					"요청을 처리하지 못했습니다.", transactionId, TransactionStatus.FAILED);
		}
		roundtripTimer.record(r.elapsedMs(), TimeUnit.MILLISECONDS);

		// 응답을 받았다 — 응답코드가 정상이면 SUCCESS, 오류 코드(없는 계좌 등)면 FAILED.
		TransactionStatus status = TransactionStatus.ofResponseCode(r.response().getResponseCode());
		// EOD 대사가 계정계측 기록과 대조할 금액을 원장에 남긴다(SUCCESS만 — 잔액이 곧 대조 수치).
		Long amount = status == TransactionStatus.SUCCESS ? parseAmount(r.response().getBalance()) : null;
		ledger.record(new LedgerRecord(transactionId, TX_CODE_BALANCE, accountNo,
				status, r.response().getResponseCode(),
				status == TransactionStatus.SUCCESS ? null : r.response().getResponseMessage(),
				requestedAt, r.elapsedMs(), correlationId(), amount));

		return ResponseEntity.ok(new BalanceRoundTrip(
				transactionId, status.name(),
				HexFormat2.toHex(r.requestFrame()), HexFormat2.toAscii(r.requestFrame()), r.requestFrame().length,
				HexFormat2.toHex(r.responseFrame()), HexFormat2.toAscii(r.responseFrame()), r.responseFrame().length,
				r.response(), r.coreHost() + ":" + r.corePort(), r.elapsedMs()));
	}

	/** 잔액 문자열을 대사용 금액(Long)으로 — 파싱 불가면 null(대사에서 금액 대조 제외). */
	private static Long parseAmount(String balance) {
		try {
			return balance == null || balance.isBlank() ? null : Long.parseLong(balance.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 현재 요청의 correlation ID(CorrelationIdFilter가 MDC에 넣어 둔 값). */
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

	/** 원인 사슬에 서킷 즉시 거절이 있는가 — 있으면 502가 아니라 503으로 구분해 돌려준다. */
	private static boolean isCircuitOpen(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof CircuitOpenException) {
				return true;
			}
		}
		return false;
	}

	// --- 요청/응답 바디 ---

	public record BalanceRequest(String accountNo) {
	}

	/**
	 * @param transactionId 이 거래에 채번된 거래고유번호(원장·로그와 같은 값)
	 * @param ledgerStatus  원장에 적힌 3값 상태
	 * @param requestHex    소켓으로 나간 요청 전문(52byte — 거래고유번호 포함) hex
	 * @param responseHex   소켓으로 돌아온 응답 전문(61byte) hex
	 * @param json          응답 전문을 파싱한 최종 JSON(DTO 직렬화)
	 * @param core          왕복한 계정계 host:port
	 * @param elapsedMs     소켓 왕복 소요 시간(ms)
	 */
	public record BalanceRoundTrip(
			String transactionId, String ledgerStatus,
			String requestHex, String requestAscii, int requestLength,
			String responseHex, String responseAscii, int responseLength,
			BalanceInquiryResponse json, String core, long elapsedMs) {
	}

	// --- 예외 매핑 ---

	/** 입력 자체가 틀린 경우는 400. */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", e.getMessage() == null ? "잘못된 요청" : e.getMessage()));
	}
}
