package io.gwanmun.web;

import io.gwanmun.gateway.GatewayException;
import io.gwanmun.gateway.GatewayService;
import io.gwanmun.gateway.GatewayService.GatewayResult;
import io.gwanmun.message.HexFormat2;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 게이트웨이 왕복 엔드포인트. REST(JSON)로 잔액조회를 받아 계정계와 전문으로 실제 소켓 왕복을 한 뒤,
 * 오간 요청/응답 전문 hex와 복원된 JSON을 함께 돌려준다(소켓을 타고 온 것임이 화면에 드러나게).
 *
 * <ul>
 *   <li>POST /api/gateway/balance — {"accountNo":"..."} → 요청 전문 hex + 응답 전문 hex + 파싱된 JSON</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

	/** 계좌번호는 숫자 1~14자리(요청 전문 계좌 필드 14byte). */
	private static final Pattern ACCOUNT_NO = Pattern.compile("\\d{1,14}");

	private final GatewayService gateway;

	public GatewayController(GatewayService gateway) {
		this.gateway = gateway;
	}

	@PostMapping("/balance")
	public BalanceRoundTrip balance(@RequestBody BalanceRequest req) {
		String accountNo = req.accountNo() == null ? "" : req.accountNo().trim();
		if (!ACCOUNT_NO.matcher(accountNo).matches()) {
			throw new IllegalArgumentException("accountNo 는 숫자 1~14자리여야 합니다. (입력='" + accountNo + "')");
		}

		GatewayResult r = gateway.balanceInquiry(accountNo);
		return new BalanceRoundTrip(
				HexFormat2.toHex(r.requestFrame()), HexFormat2.toAscii(r.requestFrame()), r.requestFrame().length,
				HexFormat2.toHex(r.responseFrame()), HexFormat2.toAscii(r.responseFrame()), r.responseFrame().length,
				r.response(), r.coreHost() + ":" + r.corePort(), r.elapsedMs());
	}

	// --- 요청/응답 바디 ---

	public record BalanceRequest(String accountNo) {
	}

	/**
	 * @param requestHex   소켓으로 나간 요청 전문(30byte) hex
	 * @param responseHex  소켓으로 돌아온 응답 전문(61byte) hex
	 * @param json         응답 전문을 파싱한 최종 JSON(DTO 직렬화)
	 * @param core         왕복한 계정계 host:port
	 * @param elapsedMs    소켓 왕복 소요 시간(ms)
	 */
	public record BalanceRoundTrip(
			String requestHex, String requestAscii, int requestLength,
			String responseHex, String responseAscii, int responseLength,
			BalanceInquiryResponse json, String core, long elapsedMs) {
	}

	// --- 예외 매핑 ---

	/** 계정계 통신 실패는 502(백엔드 장애). */
	@ExceptionHandler(GatewayException.class)
	public ResponseEntity<Map<String, String>> handleGateway(GatewayException e) {
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(Map.of("error", e.getMessage()));
	}

	/** 입력 자체가 틀린 경우는 400. */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", e.getMessage() == null ? "잘못된 요청" : e.getMessage()));
	}
}
