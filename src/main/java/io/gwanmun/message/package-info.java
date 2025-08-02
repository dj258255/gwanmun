/**
 * message 모듈 — 고정길이 전문(電文) ↔ DTO ↔ JSON 코덱. 이 프로젝트의 심장이자, 다른 모듈에
 * 의존하지 않는 순수 모듈이다(바이트 다루기만 안다). core·gateway·web이 이 모듈을 쓴다.
 *
 * <p>기반 패키지의 타입(MessageCodec·HexFormat2·MessageRegistry·예외)은 모듈 API로 공개된다.
 * DTO와 스펙 하위 패키지는 다른 모듈이 전문 필드를 다루려면 필요하므로 명시적으로 열었다
 * (각 package-info의 {@link org.springframework.modulith.NamedInterface}).
 */
@org.springframework.modulith.ApplicationModule(displayName = "message · 전문 코덱")
package io.gwanmun.message;
