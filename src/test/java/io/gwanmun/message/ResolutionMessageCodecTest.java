package io.gwanmun.message;

import io.gwanmun.message.dto.BalanceInquiryRequest;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import io.gwanmun.message.dto.NetCancelRequest;
import io.gwanmun.message.dto.NetCancelResponse;
import io.gwanmun.message.dto.TransactionStatusInquiryRequest;
import io.gwanmun.message.dto.TransactionStatusInquiryResponse;
import io.gwanmun.message.spec.MessageSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 해소 전문(거래상태조회·망취소)의 스펙을 검증한다 — 왕복 무손실과, 네 전문이
 * <b>같은 프레임 규격(요청 52 / 응답 61 byte)</b>을 지키는지. 규격이 같아야 기존 고정길이
 * 프레이밍·커넥션 풀을 그대로 타고 계정계와 오갈 수 있다.
 */
class ResolutionMessageCodecTest {

	private static final String TRAN_ID = "GWMNU20260709000012345";

	private final MessageCodec codec = new MessageCodec();

	@Test
	@DisplayName("네 전문이 같은 프레임 규격을 지킨다 — 요청은 전부 52byte, 응답은 전부 61byte")
	void allMessagesShareFrameLengths() {
		assertThat(MessageSpec.of(BalanceInquiryRequest.class).totalLength()).isEqualTo(52);
		assertThat(MessageSpec.of(TransactionStatusInquiryRequest.class).totalLength()).isEqualTo(52);
		assertThat(MessageSpec.of(NetCancelRequest.class).totalLength()).isEqualTo(52);

		assertThat(MessageSpec.of(BalanceInquiryResponse.class).totalLength()).isEqualTo(61);
		assertThat(MessageSpec.of(TransactionStatusInquiryResponse.class).totalLength()).isEqualTo(61);
		assertThat(MessageSpec.of(NetCancelResponse.class).totalLength()).isEqualTo(61);
	}

	@Test
	@DisplayName("거래상태조회 요청/응답 왕복 무손실(한글 응답 메시지 포함)")
	void statusInquiryRoundTrip() {
		TransactionStatusInquiryRequest req =
				new TransactionStatusInquiryRequest("0400", TRAN_ID, "ST01", "");
		byte[] reqRaw = codec.build(req);
		assertThat(reqRaw).hasSize(52);
		assertThat(codec.parse(reqRaw, TransactionStatusInquiryRequest.class)).isEqualTo(req);

		TransactionStatusInquiryResponse res = new TransactionStatusInquiryResponse(
				"0410", TRAN_ID, "ST01",
				TransactionStatusInquiryResponse.PROCESSED, "0000", "처리된 거래입니다", "");
		byte[] resRaw = codec.build(res);
		assertThat(resRaw).hasSize(61);
		TransactionStatusInquiryResponse parsed =
				codec.parse(resRaw, TransactionStatusInquiryResponse.class);
		assertThat(parsed).isEqualTo(res);
		assertThat(parsed.getProcessedFlag()).isEqualTo("01");
		assertThat(parsed.getResponseMessage()).isEqualTo("처리된 거래입니다");
	}

	@Test
	@DisplayName("망취소 요청/응답 왕복 무손실 — 원거래 거래ID가 그대로 반사된다")
	void netCancelRoundTrip() {
		NetCancelRequest req = new NetCancelRequest("0420", TRAN_ID, "NC01", "");
		byte[] reqRaw = codec.build(req);
		assertThat(reqRaw).hasSize(52);
		assertThat(codec.parse(reqRaw, NetCancelRequest.class)).isEqualTo(req);

		NetCancelResponse res = new NetCancelResponse(
				"0430", TRAN_ID, "NC01", NetCancelResponse.CANCELED, "0000", "취소 완료되었습니다", "");
		byte[] resRaw = codec.build(res);
		assertThat(resRaw).hasSize(61);
		NetCancelResponse parsed = codec.parse(resRaw, NetCancelResponse.class);
		assertThat(parsed).isEqualTo(res);
		assertThat(parsed.getOrigTranId()).isEqualTo(TRAN_ID);
	}
}
