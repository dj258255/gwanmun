package io.gwanmun.web;

import io.gwanmun.gateway.GatewayException;
import io.gwanmun.gateway.GatewayService;
import io.gwanmun.gateway.GatewayService.GatewayResult;
import io.gwanmun.ledger.TransactionIdGenerator;
import io.gwanmun.ledger.TransactionLedger;
import io.gwanmun.ledger.TransactionLedger.LedgerRecord;
import io.gwanmun.ledger.TransactionStatus;
import io.gwanmun.message.HexFormat2;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

	/** 계좌번호는 숫자 1~14자리(요청 전문 계좌 필드 14byte). */
	private static final Pattern ACCOUNT_NO = Pattern.compile("\\d{1,14}");

	/** 잔액조회 거래코드(요청 전문의 txCode 필드와 동일한 값). */
	private static final String TX_CODE_BALANCE = "IN01";

	private final GatewayService gateway;
	private final TransactionLedger ledger;
	private final TransactionIdGenerator txIds;
	private final Timer roundtripTimer;

	public GatewayController(GatewayService gateway, TransactionLedger ledger,
			TransactionIdGenerator txIds, MeterRegistry meterRegistry) {
		this.gateway = gateway;
		this.ledger = ledger;
		this.txIds = txIds;
		// 커스텀 메트릭: 계정계 TCP 왕복 latency 타이머(거래 종류 태그).
		this.roundtripTimer = Timer.builder("gwanmun.core.roundtrip")
				.description("계정계 TCP 소켓 왕복 소요 시간")
				.tag("tx", "balance")
				.register(meterRegistry);
	}

	@PostMapping("/balance")
	public ResponseEntity<?> balance(@RequestBody BalanceRequest req) {
		String transactionId = txIds.next(); // 결과가 어떻든 이 거래에는 ID가 있다.
		String accountNo = req.accountNo() == null ? "" : req.accountNo().trim();
		Instant requestedAt = Instant.now();

		if (!ACCOUNT_NO.matcher(accountNo).matches()) {
			// 입력 오류 — 계정계로 나가기 전의 명확한 실패이므로 FAILED로 적는다.
			ledger.record(new LedgerRecord(transactionId, TX_CODE_BALANCE, accountNo,
					TransactionStatus.FAILED, null, "입력 오류: accountNo 형식 위반",
					requestedAt, 0, correlationId()));
			throw new IllegalArgumentException("accountNo 는 숫자 1~14자리여야 합니다. (입력='" + accountNo + "')");
		}

		long startNanos = System.nanoTime();
		GatewayResult r;
		try {
			r = gateway.balanceInquiry(accountNo);
		} catch (GatewayException e) {
			long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
			// 응답을 못 받은 거래의 3값 판정 — 타임아웃/응답 없는 끊김은 UNKNOWN, 나머지는 FAILED.
			TransactionStatus status = TransactionStatus.ofFailure(e);
			ledger.record(new LedgerRecord(transactionId, TX_CODE_BALANCE, accountNo,
					status, null, e.getMessage(), requestedAt, elapsedMs, correlationId()));
			HttpStatus http = status == TransactionStatus.UNKNOWN
					? HttpStatus.GATEWAY_TIMEOUT   // 504: 응답을 못 받았다(결과 미확인)
					: HttpStatus.BAD_GATEWAY;      // 502: 백엔드 실패
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("error", e.getMessage());
			body.put("transactionId", transactionId);
			body.put("ledgerStatus", status.name());
			return ResponseEntity.status(http).body(body);
		}
		roundtripTimer.record(r.elapsedMs(), TimeUnit.MILLISECONDS);

		// 응답을 받았다 — 응답코드가 정상이면 SUCCESS, 오류 코드(없는 계좌 등)면 FAILED.
		TransactionStatus status = TransactionStatus.ofResponseCode(r.response().getResponseCode());
		ledger.record(new LedgerRecord(transactionId, TX_CODE_BALANCE, accountNo,
				status, r.response().getResponseCode(),
				status == TransactionStatus.SUCCESS ? null : r.response().getResponseMessage(),
				requestedAt, r.elapsedMs(), correlationId()));

		return ResponseEntity.ok(new BalanceRoundTrip(
				transactionId, status.name(),
				HexFormat2.toHex(r.requestFrame()), HexFormat2.toAscii(r.requestFrame()), r.requestFrame().length,
				HexFormat2.toHex(r.responseFrame()), HexFormat2.toAscii(r.responseFrame()), r.responseFrame().length,
				r.response(), r.coreHost() + ":" + r.corePort(), r.elapsedMs()));
	}

	/** 현재 요청의 correlation ID(CorrelationIdFilter가 MDC에 넣어 둔 값). */
	private static String correlationId() {
		return MDC.get(CorrelationIdFilter.MDC_KEY);
	}

	// --- 요청/응답 바디 ---

	public record BalanceRequest(String accountNo) {
	}

	/**
	 * @param transactionId 이 거래에 채번된 거래고유번호(원장·로그와 같은 값)
	 * @param ledgerStatus  원장에 적힌 3값 상태
	 * @param requestHex    소켓으로 나간 요청 전문(30byte) hex
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
