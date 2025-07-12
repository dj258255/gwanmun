package io.gwanmun.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import io.gwanmun.message.MessageRegistry;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST 엔드포인트가 전문 ↔ JSON 왕복을 실제로 처리하는지, 실패는 400으로 내는지 검증. */
@WebMvcTest(MessageController.class)
@Import(MessageRegistry.class)
class MessageControllerTest {

	@Autowired
	MockMvc mvc;

	@Test
	@DisplayName("POST /api/build → hex·아스키·길이를 반환")
	void buildEndpoint() throws Exception {
		String body = """
				{"spec":"balanceRequest","fields":{"messageType":"0200","accountNo":"12345678901234","txCode":"IN01","filler":""}}
				""";
		mvc.perform(post("/api/build").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length").value(30))
				.andExpect(jsonPath("$.hex").exists());
	}

	@Test
	@DisplayName("POST /api/parse → 파싱된 JSON 필드를 반환")
	void parseEndpoint() throws Exception {
		// "0200" + "12345678901234" + "IN01" + 공백8 을 hex로 (모두 ASCII).
		String hex = "30323030" + "3132333435363738393031323334" + "494E3031" + "2020202020202020";
		String body = "{\"spec\":\"balanceRequest\",\"hex\":\"" + hex + "\"}";
		mvc.perform(post("/api/parse").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.messageType").value("0200"))
				.andExpect(jsonPath("$.accountNo").value("12345678901234"))
				.andExpect(jsonPath("$.txCode").value("IN01"));
	}

	@Test
	@DisplayName("잘못된 길이의 전문 → 400과 에러 메시지")
	void parseBadLengthReturns400() throws Exception {
		String body = "{\"spec\":\"balanceRequest\",\"hex\":\"3030\"}";
		mvc.perform(post("/api/parse").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error", containsString("길이 불일치")));
	}
}
