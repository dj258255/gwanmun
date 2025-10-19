// 부하 실측 시나리오 (a)/(c) — POST /api/gateway/balance 에 고정 도착률(constant-arrival-rate)을
// 걸어 한계 TPS·P95 곡선을 그린다. 목표는 "N TPS 달성"이 아니라 어느 지점에서 P95가 무너지고
// 무엇이 병목인지를 드러내는 것이다(우아한형제들식 피크 역산).
//
// 시나리오 (a): 정상 백엔드. RATE를 올려 가며 P95가 꺾이는 무릎을 찾는다.
//   k6 run -e RATE=1000 -e DURATION=20s loadtest/gw_balance.js
// 시나리오 (c): 죽은 백엔드(서킷 OPEN). 즉시 거절(503)의 처리량·지연을 잰다.
//   앱을 죽은 포트로 띄운 뒤 동일 스크립트 실행 — 응답이 503으로 바뀐다.
import http from 'k6/http';
import { check } from 'k6';

const RATE = Number(__ENV.RATE || 1000);
const DURATION = __ENV.DURATION || '20s';
const BASE = __ENV.BASE || 'http://127.0.0.1:8090';
const API_KEY = __ENV.API_KEY || 'demo-key-fintech-a';
const ACCOUNT = __ENV.ACCOUNT || '10000000001';

export const options = {
  discardResponseBodies: true,
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Number(__ENV.PREVUS || 200),
      maxVUs: Number(__ENV.MAXVUS || 3000),
    },
  },
};

const params = {
  headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEY },
};
const body = JSON.stringify({ accountNo: ACCOUNT });

export default function () {
  const res = http.post(`${BASE}/api/gateway/balance`, body, params);
  // 200(정상)·503(서킷 OPEN 즉시 거절) 모두 "게이트웨이가 응답한 것" — 어느 쪽인지만 구분한다.
  check(res, {
    '2xx': (r) => r.status >= 200 && r.status < 300,
    '503 fast-reject': (r) => r.status === 503,
  });
}
