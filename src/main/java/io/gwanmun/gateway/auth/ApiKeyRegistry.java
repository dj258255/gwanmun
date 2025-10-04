package io.gwanmun.gateway.auth;

import io.gwanmun.gateway.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API 키 → 클라이언트 매핑 레지스트리(인메모리). 등록된 키만 알려진 클라이언트로 인정한다.
 *
 * <p>설정({@link GatewayProperties#getApiKeys()})에서 채우되, 비어 있으면 데모용 기본 키 두 개를
 * 심는다 — 설정 없이도 화면·curl 데모가 돌게 하기 위함이다. 실서비스라면 키를 코드/설정이 아니라
 * 시크릿 스토어에 두고 해시로 대조하겠지만, 학습판의 경계로 남긴다.
 */
@Component
public class ApiKeyRegistry {

	private static final Logger log = LoggerFactory.getLogger(ApiKeyRegistry.class);

	private final Map<String, String> keyToClient;

	public ApiKeyRegistry(GatewayProperties props) {
		Map<String, String> map = new LinkedHashMap<>();
		if (props.getApiKeys() == null || props.getApiKeys().isEmpty()) {
			map.put("demo-key-fintech-a", "fintech-a");
			map.put("demo-key-fintech-b", "fintech-b");
			// fail-open 지점 — 설정이 비면 "누구나 아는 데모 키"가 살아난다. 학습판 편의로 유지하되,
			// 조용히 켜지지 않게 WARN으로 못 박는다(운영이라면 여기서 기동 실패가 맞다).
			log.warn("API 키 설정이 비어 데모 기본 키 {}개를 자동 활성합니다(fail-open) — "
					+ "운영 환경이라면 gwanmun.gateway.api-keys 를 반드시 설정하세요.", map.size());
		} else {
			map.putAll(props.getApiKeys());
		}
		// 키 원문은 크리덴셜이다 — 기동 로그에는 클라이언트 id만 남긴다(B1).
		log.info("API 키 {}개 로드 (클라이언트: {})", map.size(), map.values());
		this.keyToClient = Map.copyOf(map);
	}

	/** 키에 매핑된 클라이언트 id. 등록되지 않은 키면 null. */
	public String clientFor(String apiKey) {
		if (apiKey == null) {
			return null;
		}
		return keyToClient.get(apiKey);
	}
}
