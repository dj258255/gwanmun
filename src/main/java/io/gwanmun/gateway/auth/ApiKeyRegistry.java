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
			log.info("API 키 설정이 비어 데모 기본 키 2개를 사용합니다: {}", map.keySet());
		} else {
			map.putAll(props.getApiKeys());
			log.info("API 키 {}개 로드: {}", map.size(), map.keySet());
		}
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
