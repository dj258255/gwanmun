/**
 * ledger 모듈 — 관측 가능한 거래 원장. 게이트웨이를 지나간 모든 거래에 유일한 거래고유번호를 채번하고,
 * 거래ID·거래코드·상태·소요시간을 DB에 <b>비동기로</b> 적재한다(적재가 거래 지연을 만들지 않게).
 *
 * <p>상태는 반드시 3값이다 — SUCCESS(응답을 정상 수신) / FAILED(명확한 오류 응답·입력 오류) /
 * <b>UNKNOWN</b>(타임아웃 등 응답을 못 받은 경우). 응답을 못 받은 거래를 임의로 실패 처리하지 않는 것이
 * 금융 연계의 핵심이다 — 계정계에서는 처리됐을 수 있다.
 *
 * <p>계좌번호는 저장 직전 마스킹해 원장에는 원문이 남지 않는다({@code io.gwanmun.message.AccountMasker}).
 * 영속 계층(store 하위 패키지)은 모듈 내부로 감추고, 채번기·원장 서비스·상태만 모듈 API로 노출한다.
 * message 모듈(마스커)에만 의존하고 core·gateway·web은 모른다(한 방향 의존).
 */
@org.springframework.modulith.ApplicationModule(displayName = "ledger · 거래 원장")
package io.gwanmun.ledger;
