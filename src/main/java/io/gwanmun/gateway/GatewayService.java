package io.gwanmun.gateway;

import io.gwanmun.message.AccountMasker;
import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.dto.BalanceInquiryRequest;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.gwanmun.core.CoreBankingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * 게이트웨이 왕복의 배선. REST(JSON)로 들어온 잔액조회를 계정계 전문 왕복으로 통역한다.
 *
 * <pre>
 *  accountNo + 거래ID(JSON)
 *    → 요청 전문 build(52byte)   [MessageCodec]
 *    → TCP 송신·응답 수신(프레이밍) [CoreBankingClient]
 *    → 응답 전문 parse(61byte)    [MessageCodec]
 *    → 결과(JSON)
 * </pre>
 *
 * 관문은 흐름만 통제하고 잔액 계산은 계정계에 위임한다. 여기서는 전문을 만들고, 소켓으로 보내고,
 * 돌아온 전문을 되읽을 뿐이다.
 */
@Service
public class GatewayService {

	private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

	// 잔액조회 요청 전문의 고정 필드.
	private static final String REQUEST_MESSAGE_TYPE = "0200";
	private static final String TX_CODE_BALANCE = "IN01";

	private final CoreBankingClient client;
	private final MessageCodec codec = new MessageCodec();

	public GatewayService(CoreBankingClient client) {
		this.client = client;
	}

	/**
	 * 잔액조회 한 건을 계정계와 전문으로 왕복하고, 오간 전문 바이트와 파싱 결과를 함께 담아 돌려준다.
	 *
	 * <p>Phase 6부터 거래고유번호를 요청 전문에 싣는다 — 계정계가 이 열쇠로 거래를 기억해야,
	 * 응답을 못 받은 거래(UNKNOWN)를 나중에 상태조회·망취소로 확정 지을 수 있다.
	 */
	public GatewayResult balanceInquiry(String accountNo, String transactionId) {
		BalanceInquiryRequest request = new BalanceInquiryRequest(
				REQUEST_MESSAGE_TYPE, transactionId, accountNo, TX_CODE_BALANCE, "");
		byte[] requestFrame = codec.build(request);

		long startNanos = System.nanoTime();
		byte[] responseFrame;
		try {
			// PoolExhaustedException(내부 풀 고갈)은 여기서 잡지 않고 그대로 올린다 — 계정계 실패가
			// 아니라 게이트웨이 내부 사정이라, 호출 측이 503(+원장 FAILED)으로 따로 다뤄야 한다(Phase 7).
			responseFrame = client.exchange(requestFrame);
		} catch (IOException e) {
			throw new GatewayException(
					"계정계(" + client.host() + ":" + client.port() + ") 통신 실패: " + e.getMessage(), e);
		}
		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

		BalanceInquiryResponse response;
		try {
			response = codec.parse(responseFrame, BalanceInquiryResponse.class);
		} catch (RuntimeException e) {
			// 왕복은 성공했는데 응답 전문이 스펙과 다르다(잘림·쓰레기). RuntimeException으로 관통시키면
			// 컨트롤러의 원장 기록 경로를 건너뛰어 원장에 구멍이 난다 — 게이트웨이 예외로 감싼다(Phase 7).
			throw new GatewayException("계정계 응답 전문 처리 실패(왕복은 성공): " + e.getMessage(), e);
		}
		// 앱 로그에도 계좌 원문을 남기지 않는다(마스킹 규칙: 앞6+뒤4만 노출).
		log.info("게이트웨이 왕복 완료: 계좌={} 응답코드={} 잔액={} ({}ms)",
				AccountMasker.mask(accountNo), response.getResponseCode(), response.getBalance(), elapsedMs);

		return new GatewayResult(requestFrame, responseFrame, response,
				client.host(), client.port(), elapsedMs);
	}

	/**
	 * 왕복 결과. 소켓을 실제로 타고 오간 전문 바이트를 그대로 들고 있어(hex로 보여줄 수 있게),
	 * "인메모리 변환이 아니라 소켓 통신"임이 드러나게 한다.
	 */
	public record GatewayResult(
			byte[] requestFrame,
			byte[] responseFrame,
			BalanceInquiryResponse response,
			String coreHost,
			int corePort,
			long elapsedMs
	) {
	}
}
