/**
 * web 모듈 — REST 컨트롤러. 아래 모듈들(message·core·gateway)을 조립해 HTTP로 노출만 한다.
 * 비즈니스 판단은 아래 모듈에 위임하고, 여기서는 요청/응답 형태만 책임진다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "web · REST 조립")
package io.gwanmun.web;
