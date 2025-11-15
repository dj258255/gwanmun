# DBTower 연계 — 거래 원장 PG를 관제 대상으로 (9단계)

gwanmun의 거래 원장(PostgreSQL, 호스트 포트 25432, 컨테이너 `gwanmun-ledger-db`)을
**DBTower**([dj258255/dbtower](https://github.com/dj258255/dbtower)) 관제탑의 관측 대상으로
등록하는 실행안이다. 실제로 등록 가능한 상태까지 **대상 DB를 준비하고 실측으로 증명**했다.

## 왜 이 둘을 잇는가 — 정반대 성격의 느슨한 연결

| | gwanmun | DBTower |
|---|---|---|
| 위치 | 데이터 경로 **위**(인라인) — 전문을 중계 | 데이터 경로 **밖**(아웃오브밴드) — 관찰 |
| 하는 일 | REST↔전문 통역, 관문(인증·유량제어), 원장 적재 | 이기종 DB 모니터링·쿼리통계·실행계획·이상감지 |
| 원장 PG를 보는 눈 | 자기가 **쓰는** 저장소 | 남의 인스턴스로 **관측**하는 대상 |

성격이 정반대라 [ROADMAP](ROADMAP.md)의 정체성 경계대로 **별도 저장소**로 두고, 연결은
**느슨하게** — DBTower가 원장 PG를 하나의 인스턴스로 등록해 관측만 한다. gwanmun 코드는
DBTower를 전혀 모른다(의존 0). 연결은 순전히 운영 구성이다.

## 관제가 잡아 줄 것 — 부하가 원장에 남기는 흔적

8단계 부하 실측에서 게이트웨이 처리량 천장은 웹 계층(blocking thread-per-request)이었지만,
원장 **insert**는 모든 거래 경로가 지나는 비동기 적재 지점이다. 부하가 커지면 여기서:

- **원장 insert 경합·슬로우쿼리** — `transaction_ledger`로의 insert가 느려지면 적재 큐가 밀리고
  (7단계 `gwanmun.ledger.dropped` 카운터가 유실을 세지만, **왜** 느린지는 못 본다).
- **락 대기** — 대사(reconciliation)의 UPDATE(UNKNOWN→CANCELED)와 적재 insert가 겹치는 구간.
- **인덱스 효율** — `idx_ledger_tx_id`(unique)·`idx_ledger_status` 사용 여부, 대사의 접두어 스캔.

이건 gwanmun 자신은 **안에서 못 보는** 것들이다(자기 커넥션의 지연은 알아도, DB 서버 관점의
경합·락·플랜은 모른다). DBTower가 밖에서 `pg_stat_statements`·`pg_stat_activity`·락 뷰로 본다.

## 대상 DB 준비 (실측 완료)

DBTower는 대상 DB에 **최소 권한** 모니터 계정으로 접속한다([least-privilege](https://github.com/dj258255/dbtower/blob/main/docs/least-privilege.md):
PostgreSQL은 `LOGIN` 롤 + `pg_read_all_stats`면 쿼리통계까지 통과). 쿼리통계 소스인
`pg_stat_statements`는 공유 라이브러리라 기동 시 로드돼야 한다.

```sql
-- 1) pg_stat_statements 로드 (docker-compose.yml에 반영: command 로 shared_preload_libraries 지정)
--    이미 뜬 컨테이너라면: ALTER SYSTEM SET shared_preload_libraries='pg_stat_statements'; 후 재시작
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- 2) DBTower 최소 권한 모니터 계정
CREATE ROLE dbtower_monitor LOGIN PASSWORD 'dbtower1234';
GRANT pg_read_all_stats TO dbtower_monitor;   -- 쿼리통계/슬로우쿼리 마스킹 해제
```

`docker-compose.yml`에는 재현 가능하게 로드를 못 박아 두었다:

```yaml
command: ["postgres", "-c", "shared_preload_libraries=pg_stat_statements"]
```

## DBTower에 등록 (POST /api/instances)

DBTower를 띄운 뒤(관제탑은 자기 컨트롤 플레인 DB를 따로 가진다) 이 한 번의 호출로 원장 PG가
관측 대상이 된다 — 등록 시점에 **접속 검증(health)**이 돌아 통과해야 저장된다:

```
POST /api/instances
{
  "name": "gwanmun-ledger",
  "type": "POSTGRESQL",
  "host": "127.0.0.1",
  "port": 25432,
  "dbName": "gwanmun",
  "username": "dbtower_monitor",
  "password": "dbtower1234",
  "useTls": false
}
```

등록되면 `GET /api/instances/{id}/health`·`/query-stats`·`/table-stats`·`/replication`으로 원장 PG의
버전·응답시간·쿼리 랭킹(load%)·테이블 통계·복제 상태가 한 API로 나온다.

## 실측 — 대상 DB가 관제 준비됐음을 증명

부팅한 DBTower 앱까지 붙이지는 않았다(관제탑은 Spring Boot 4 + 자체 컨트롤 플레인 DB가 필요한
별도 스택이다). 대신 **DBTower가 접속·조회할 그 계정으로, 그 쿼리를 실제로 돌려** 원장 PG가
등록 가능한 상태임을 확인했다.

```
# 모니터 계정 접속(등록 시 health check와 동일한 경로)
$ PGPASSWORD=dbtower1234 psql -U dbtower_monitor -d gwanmun -c "SELECT version();"
  PostgreSQL 16.14 on aarch64-unknown-linux-musl ...      # 접속·조회 OK

# 원장 insert 트래픽을 흘린 뒤, 모니터 계정으로 pg_stat_statements 조회(=DBTower query-stats가 보는 것)
$ PGPASSWORD=dbtower1234 psql -U dbtower_monitor -d gwanmun -c \
   "SELECT calls, round(mean_exec_time::numeric,3) AS mean_ms, left(query,58)
    FROM pg_stat_statements WHERE query ILIKE '%transaction_ledger%' ORDER BY calls DESC;"

 calls | mean_ms |                         left
-------+---------+------------------------------------------------------------
     5 |   0.820 | insert into transaction_ledger (account_masked,amount,corr
```

`shared_preload_libraries=pg_stat_statements` 로드 확인, 모니터 계정 로그인 OK,
원장 insert가 `pg_stat_statements`에 집계되고 `dbtower_monitor`가 그걸 읽는다 — DBTower가
원장 PG를 등록하면 그대로 관측할 수치다.

## 정직한 경계

- DBTower **앱 자체를 기동해 등록 API를 호출하지는 않았다.** 위 등록 body는 DBTower의 실제
  요청 스펙([`RegisterRequest`](https://github.com/dj258255/dbtower/blob/main/src/main/java/io/dbtower/registry/RegistryController.java))
  이고, 대상 DB는 DBTower가 쓸 계정·확장·권한으로 실제 조회까지 확인했다. "이렇게 연결된다"가
  아니라 "여기까지 준비됐고, 관제가 볼 수치를 그 계정으로 실제로 봤다"까지다.
- 원장 PG의 모니터 계정은 데모용 평문 비밀번호다(실서비스라면 시크릿 스토어).
- gwanmun 코드는 DBTower를 참조하지 않는다 — 연결은 구성(이 문서 + docker-compose)뿐이고,
  두 프로젝트의 정체성 경계는 유지된다.
