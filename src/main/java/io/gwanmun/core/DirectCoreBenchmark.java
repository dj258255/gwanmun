package io.gwanmun.core;

import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.dto.BalanceInquiryRequest;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 부하 실측 시나리오 (b) — <b>게이트웨이 경유 오버헤드</b>를 재기 위한 기준선 (Phase 8).
 *
 * <p>k6가 재는 {@code POST /api/gateway/balance}의 지연에는 HTTP 파싱 · 관문 필터 체인(인증·라우팅·
 * 유량제어) · 전문 build · 원장 비동기 적재 · JSON 직렬화가 모두 얹혀 있다. 이 도구는 그 껍데기를
 * 벗기고 <b>{@link CoreBankingClient}로 목업 계정계에 직접 붙어</b> 순수 TCP 왕복(+프레이밍+커넥션 풀)만
 * 잰다. 두 지연의 차이가 "게이트웨이를 경유하는 값"이다.
 *
 * <p>게이트웨이와 같은 클라이언트 코드를 재사용한다 — 별도 소켓 코드를 새로 짜면 비교가 공정하지 않다.
 * ({@link MockCoreBankingServer}처럼 core 모듈 안에 두어 모듈 경계를 새로 만들지 않는다.)
 *
 * <pre>
 *   ./gradlew runDirectBenchmark --args="127.0.0.1 9099 20000 2000 1"
 *                                        host      port iters warmup threads
 * </pre>
 */
public final class DirectCoreBenchmark {

	private static final String REQUEST_MESSAGE_TYPE = "0200";
	private static final String TX_CODE_BALANCE = "IN01";
	private static final String ACCOUNT_NO = "10000000001";
	// 22자 거래고유번호(GWMN + U + 날짜8 + 일련번호9). 왕복 자체를 재는 목적이라 고정값을 쓴다.
	private static final String TRAN_ID = "GWMNU20260709000000001";

	public static void main(String[] args) throws Exception {
		String host = args.length > 0 ? args[0] : "127.0.0.1";
		int port = args.length > 1 ? Integer.parseInt(args[1]) : 9099;
		int iterations = args.length > 2 ? Integer.parseInt(args[2]) : 20_000;
		int warmup = args.length > 3 ? Integer.parseInt(args[3]) : 2_000;
		int threads = args.length > 4 ? Integer.parseInt(args[4]) : 1;

		MessageCodec codec = new MessageCodec();
		byte[] requestFrame = codec.build(new BalanceInquiryRequest(
				REQUEST_MESSAGE_TYPE, TRAN_ID, ACCOUNT_NO, TX_CODE_BALANCE, ""));

		// 게이트웨이와 동일한 커넥션 풀·프레이밍을 쓰되, 재시도·데드라인은 왕복 측정에 방해되지 않게
		// 넉넉히 둔다(none = 재시도 0, 서킷 사실상 비활성). 풀 크기는 스레드 수에 맞춘다.
		ResilienceSettings resilience = ResilienceSettings.none(5_000);
		try (CoreBankingClient client = new CoreBankingClient(host, port, 2_000, 5_000,
				Math.max(threads, 4), 2_000, 30_000, resilience)) {

			System.out.printf("직접 왕복 벤치마크: %s:%d iters=%d warmup=%d threads=%d%n",
					host, port, iterations, warmup, threads);

			// 워밍업 — JIT·소켓 초기화 비용을 측정에서 뺀다.
			for (int i = 0; i < warmup; i++) {
				client.exchange(requestFrame, TransactionKind.INQUIRY);
			}

			long[] latencies = new long[iterations];
			AtomicInteger index = new AtomicInteger();
			AtomicInteger errors = new AtomicInteger();

			long wallStart = System.nanoTime();
			if (threads <= 1) {
				for (int i = 0; i < iterations; i++) {
					latencies[i] = timeOne(client, requestFrame, errors);
				}
			} else {
				CountDownLatch done = new CountDownLatch(threads);
				int perThread = iterations / threads;
				for (int t = 0; t < threads; t++) {
					new Thread(() -> {
						for (int i = 0; i < perThread; i++) {
							int slot = index.getAndIncrement();
							if (slot >= latencies.length) {
								break;
							}
							latencies[slot] = timeOne(client, requestFrame, errors);
						}
						done.countDown();
					}).start();
				}
				done.await();
			}
			long wallNs = System.nanoTime() - wallStart;

			int n = threads <= 1 ? iterations : Math.min(index.get(), latencies.length);
			report(Arrays.copyOf(latencies, n), wallNs, errors.get());
		}
	}

	private static long timeOne(CoreBankingClient client, byte[] frame, AtomicInteger errors) {
		long s = System.nanoTime();
		try {
			client.exchange(frame, TransactionKind.INQUIRY);
		} catch (Exception e) {
			errors.incrementAndGet();
		}
		return System.nanoTime() - s;
	}

	private static void report(long[] latencies, long wallNs, int errors) {
		Arrays.sort(latencies);
		int n = latencies.length;
		double wallSec = wallNs / 1_000_000_000.0;
		System.out.printf("완료: %d건, 오류 %d건, 벽시계 %.2fs, 처리량 %.0f req/s%n",
				n, errors, wallSec, n / wallSec);
		System.out.printf("지연(ms): min=%.3f p50=%.3f p90=%.3f p95=%.3f p99=%.3f max=%.3f mean=%.3f%n",
				ms(latencies[0]), ms(pct(latencies, 50)), ms(pct(latencies, 90)),
				ms(pct(latencies, 95)), ms(pct(latencies, 99)), ms(latencies[n - 1]), msMean(latencies));
	}

	private static long pct(long[] sorted, int p) {
		int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
	}

	private static double ms(long ns) {
		return ns / 1_000_000.0;
	}

	private static double msMean(long[] arr) {
		long sum = 0;
		for (long v : arr) {
			sum += v;
		}
		return sum / (double) arr.length / 1_000_000.0;
	}

	private DirectCoreBenchmark() {
	}
}
