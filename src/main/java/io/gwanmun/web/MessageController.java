package io.gwanmun.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gwanmun.message.GwanmunBuildException;
import io.gwanmun.message.GwanmunParseException;
import io.gwanmun.message.HexFormat2;
import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.MessageRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 전문 ↔ JSON 왕복을 눈으로 보여주는 REST 엔드포인트.
 *
 * <ul>
 *   <li>POST /api/build — JSON 필드 → 고정길이 전문(hex + 아스키 덤프)</li>
 *   <li>POST /api/parse — 전문 hex → 파싱된 JSON</li>
 *   <li>GET  /api/specs — 등록된 전문 스펙 목록</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class MessageController {

	private final MessageCodec codec = new MessageCodec();
	private final MessageRegistry registry;
	private final ObjectMapper objectMapper;

	public MessageController(MessageRegistry registry, ObjectMapper objectMapper) {
		this.registry = registry;
		this.objectMapper = objectMapper;
	}

	@GetMapping("/specs")
	public java.util.Set<String> specs() {
		return registry.names();
	}

	/** JSON 필드 → 전문. 응답에 hex·아스키·byte 길이를 함께 준다. */
	@PostMapping("/build")
	public BuildResponse build(@RequestBody BuildRequest req) {
		Class<?> dtoType = registry.resolve(req.spec());
		Object dto = objectMapper.convertValue(req.fields(), dtoType);
		byte[] raw = codec.build(dto);
		return new BuildResponse(HexFormat2.toHex(raw), HexFormat2.toAscii(raw), raw.length);
	}

	/** 전문 hex → 파싱된 DTO(JSON 직렬화). */
	@PostMapping("/parse")
	public Object parse(@RequestBody ParseRequest req) {
		Class<?> dtoType = registry.resolve(req.spec());
		byte[] raw = HexFormat2.fromHex(req.hex());
		return codec.parse(raw, dtoType);
	}

	// --- 요청/응답 바디 ---

	public record BuildRequest(String spec, Map<String, Object> fields) {
	}

	public record BuildResponse(String hex, String ascii, int length) {
	}

	public record ParseRequest(String spec, String hex) {
	}

	// --- 예외 → 400 (조용한 실패 금지: 무엇이 왜 틀렸는지 메시지로) ---

	@ExceptionHandler({GwanmunParseException.class, GwanmunBuildException.class, IllegalArgumentException.class})
	public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
	}
}
