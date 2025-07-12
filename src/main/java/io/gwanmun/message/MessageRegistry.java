package io.gwanmun.message;

import io.gwanmun.message.dto.BalanceInquiryRequest;
import io.gwanmun.message.dto.BalanceInquiryResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 전문 이름 → DTO 클래스 매핑. 데모/REST가 문자열 스펙명으로 DTO 타입을 찾을 수 있게 한다.
 * (Phase 2에서 거래코드 → 스펙 라우팅으로 확장될 지점.)
 */
@Component
public class MessageRegistry {

	private final Map<String, Class<?>> specs = new LinkedHashMap<>();

	public MessageRegistry() {
		specs.put("balanceRequest", BalanceInquiryRequest.class);
		specs.put("balanceResponse", BalanceInquiryResponse.class);
	}

	/** 스펙명으로 DTO 클래스를 찾는다. 없으면 명확히 실패. */
	public Class<?> resolve(String specName) {
		Class<?> type = specs.get(specName);
		if (type == null) {
			throw new GwanmunParseException(
					"알 수 없는 전문 스펙: '" + specName + "'. 사용 가능: " + specs.keySet());
		}
		return type;
	}

	/** 등록된 스펙명 목록(데모 화면 드롭다운용). */
	public java.util.Set<String> names() {
		return specs.keySet();
	}
}
