package io.gwanmun.gateway;

import io.gwanmun.core.CoreBankingClient;
import io.gwanmun.core.TransactionKind;
import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.dto.NetCancelRequest;
import io.gwanmun.message.dto.NetCancelResponse;
import io.gwanmun.message.dto.TransactionStatusInquiryRequest;
import io.gwanmun.message.dto.TransactionStatusInquiryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * UNKNOWN 거래 해소 플로우 (Phase 6). 금융 연계의 정수 —
 * <b>응답을 못 받은 거래는 "모름"이고, 확정은 조회로 한다.</b>
 *
 * <pre>
 *  UNKNOWN 거래
 *    → ① 거래상태조회 전문(0400) 발사              [조회성 — 재시도 허용]
 *        ├─ 계정계 "미처리(02)" → FAILED로 확정 가능 (처리됐을 가능성 0)
 *        └─ 계정계 "처리됨(01)"
 *            → ② 망취소 전문(0420) 발사             [변경성 — 재시도 금지]
 *                ├─ "취소 성공(01)" → CANCELED로 확정 (원거래 무효화, 양쪽 장부 재일치)
 *                └─ "원거래 없음(02)" → UNKNOWN 유지 (상태 불일치 — 정직하게 미해소)
 * </pre>
 *
 * <p>정책 메모: 상태조회가 "처리됨"일 때 CONFIRMED(성공 확정)가 아니라 <b>망취소</b>를 택했다 —
 * 게이트웨이는 호출자에게 이미 오류(504)를 돌려줬으므로, 호출자가 모르는 성공을 살려두면 양쪽 장부가
 * 어긋난다. 원거래를 무효화해서 "없던 일"로 맞추는 것이 오픈뱅킹 망취소의 관례다.
 *
 * <p>이 서비스는 원장(ledger)을 모른다 — 전문 왕복과 판정만 하고, 원장 상태 확정은 조립층(web)이
 * {@link Resolution}을 보고 한다(모듈 경계: gateway → core·message).
 */
@Service
public class TransactionResolutionService {

	private static final Logger log = LoggerFactory.getLogger(TransactionResolutionService.class);

	private static final String MSG_STATUS_REQUEST = "0400";
	private static final String TX_CODE_STATUS = "ST01";
	private static final String MSG_CANCEL_REQUEST = "0420";
	private static final String TX_CODE_CANCEL = "NC01";

	private final CoreBankingClient client;
	private final MessageCodec codec = new MessageCodec();

	public TransactionResolutionService(CoreBankingClient client) {
		this.client = client;
	}

	/** 해소 판정 — 원장에 무엇으로 확정할지는 이 세 값이 말한다. */
	public enum Resolution {
		/** 계정계 미처리 확인 → FAILED로 확정 가능. */
		CONFIRMED_UNPROCESSED,
		/** 계정계 처리됨 → 망취소 성공 → CANCELED로 확정 가능. */
		NET_CANCELED,
		/** 상태조회는 처리됨인데 망취소가 원거래를 못 찾음 → UNKNOWN 유지(미해소). */
		CANCEL_REJECTED
	}

	/**
	 * UNKNOWN 거래 하나를 해소한다 — 상태조회로 확인하고, 처리됐으면 망취소로 무효화한다.
	 *
	 * @throws GatewayException 상태조회·망취소 전문 왕복 자체가 실패했을 때(원장은 UNKNOWN 유지 —
	 *                          해소는 멱등이라 나중에 다시 시도하면 된다)
	 */
	public ResolutionOutcome resolve(String tranId) {
		// ① 거래상태조회 — 조회성이라 재시도해도 안전하다.
		byte[] statusReqFrame = codec.build(new TransactionStatusInquiryRequest(
				MSG_STATUS_REQUEST, tranId, TX_CODE_STATUS, ""));
		byte[] statusResFrame;
		try {
			statusResFrame = client.exchange(statusReqFrame, TransactionKind.INQUIRY);
		} catch (IOException e) {
			throw new GatewayException("거래상태조회 실패(원장은 UNKNOWN 유지, 재시도 가능): " + e.getMessage(), e);
		}
		TransactionStatusInquiryResponse status =
				codec.parse(statusResFrame, TransactionStatusInquiryResponse.class);
		boolean processedAtCore = TransactionStatusInquiryResponse.PROCESSED.equals(status.getProcessedFlag());
		Leg statusLeg = new Leg(statusReqFrame, statusResFrame, status.getResponseMessage());

		if (!processedAtCore) {
			// 계정계가 받은 적 없다 — 처리됐을 가능성이 0이므로 이제야 FAILED로 확정할 수 있다.
			log.info("해소: 원거래={} 상태조회=미처리 → FAILED 확정 가능", tranId);
			return new ResolutionOutcome(tranId, false, Resolution.CONFIRMED_UNPROCESSED, statusLeg, null);
		}

		// ② 망취소 — 변경성이므로 재시도 금지(exchange가 MUTATION으로 강제).
		byte[] cancelReqFrame = codec.build(new NetCancelRequest(
				MSG_CANCEL_REQUEST, tranId, TX_CODE_CANCEL, ""));
		byte[] cancelResFrame;
		try {
			cancelResFrame = client.exchange(cancelReqFrame, TransactionKind.MUTATION);
		} catch (IOException e) {
			// 망취소마저 응답을 못 받으면 취소 여부도 모른다 — UNKNOWN 유지, 상태조회부터 다시.
			throw new GatewayException("망취소 실패(재시도 금지 — 원장은 UNKNOWN 유지, 상태조회부터 재시도): "
					+ e.getMessage(), e);
		}
		NetCancelResponse cancel = codec.parse(cancelResFrame, NetCancelResponse.class);
		boolean canceled = NetCancelResponse.CANCELED.equals(cancel.getCancelResult());
		Leg cancelLeg = new Leg(cancelReqFrame, cancelResFrame, cancel.getResponseMessage());

		Resolution resolution = canceled ? Resolution.NET_CANCELED : Resolution.CANCEL_REJECTED;
		log.info("해소: 원거래={} 상태조회=처리됨 → 망취소={} → {}",
				tranId, canceled ? "성공" : "원거래 없음", resolution);
		return new ResolutionOutcome(tranId, true, resolution, statusLeg, cancelLeg);
	}

	/** 전문 왕복 한 다리(요청/응답 바이트 + 계정계 메시지) — 화면에 hex로 드러낼 수 있게 그대로 든다. */
	public record Leg(byte[] requestFrame, byte[] responseFrame, String coreMessage) {
	}

	/**
	 * 해소 결과. {@code netCancel}은 상태조회가 "처리됨"이었을 때만 있다(미처리면 망취소를 쏠 이유가 없다).
	 */
	public record ResolutionOutcome(
			String tranId,
			boolean processedAtCore,
			Resolution resolution,
			Leg statusInquiry,
			Leg netCancel
	) {
	}
}
