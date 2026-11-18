# Testes

| Módulo | Comando | Conteúdo | Resultado (última execução) |
|---|---|---|---|
| Backend | `cd backend && ./mvnw test` | JUnit 5 + Mockito + Testcontainers (**PostgreSQL 17 e Redis reais, nunca H2**) | **44 testes, 0 falhas** |
| BFF | `cd bff && npm test` | Jest + Supertest contra um backend falso | **10 testes, 0 falhas** |
| Frontend | `cd frontend && npm test` | Vitest (Angular 21) | **55 testes, 0 falhas** |
| E2E | `cd frontend && npx playwright install chromium && npm run e2e` | Playwright contra a stack Docker em `http://localhost:4200` | **6 testes, 0 falhas** |

Pré-requisito do backend: Docker em execução (Testcontainers).

## Backend

| Classe | Cobre |
|---|---|
| `BankWithdrawalIT` | Lost update determinístico (repetido 3×), RR → 40001, optimistic com conflito real e retry, pessimistic com espera **observada** em `pg_blocking_pids`, atomic update, serializable, synchronized/ReentrantLock/advisory, contenção com 8 threads × 40 transações |
| `ConcurrencyScenariosIT` | Overselling/estoque negativo/atomic, double booking/23505, idempotência com resposta reaproveitada, deadlock 40P01 + grafo do ciclo, retry após deadlock, lock ordering, write skew RR × SERIALIZABLE × FOR UPDATE × advisory, SKIP LOCKED/claim/idempotente, outbox (perda, duplicidade, consumidor idempotente) |
| `IsolationLevelsIT` | Os 4 níveis: dirty read nunca; non-repeatable e phantom só em RC/RU; RU efetivo = RC |
| `RedisLocksIT` | Redis lock real, expiração do lease com dois donos, fencing token rejeitando o dono obsoleto |
| `EngineLifecycleIT` | Passo a passo (checkpoints liberados um a um), cancelamento com `pg_cancel_backend` e sessões liberadas, experimentos simultâneos sem interferência, sequência de eventos contínua incluindo rollbacks, reexecução com novos dados, comparador com warm-up, exclusão do namespace |
| `ApiIT` | Validação de limites (400 + correlation id), estratégia não suportada, catálogo consistente (33 presets, 8 demos), código exibido = código executado, endpoints de dashboard/inspector/system/OpenAPI |
| `EngineUnitTest` | ConsistencyValidator, SqlErrorClassifier (SQLSTATEs, Mockito), Rendezvous (liberação por bloqueio, timeout), parser do DETAIL de deadlock, RecommendationEngine |

Testes são determinísticos: a interleaving crítica é forçada por `CountDownLatch`/`Rendezvous` e confirmada
pelo banco, não por `sleep`.

## BFF

Proxy REST (corpo, query, correlation id gerado e propagado), repasse de erros do backend, export binário com
`Content-Disposition`, roteamento por header/query e instância desconhecida, agregação `/api/instances`,
**SSE chegando em frames separados**, `Last-Event-ID` repassado (reconexão), **abort do upstream quando o
navegador desconecta**, 503 `BACKEND_UNAVAILABLE`.

## Frontend

Validação/limites do formulário de configuração, construção das lanes da timeline, `SseService`
(eventos, deduplicação por sequência, reconexão com `lastEventId`, heartbeat) com `EventSource` simulado,
mapeamento da tabela do comparador, apresentação do estado (Initial/Current/Expected), fluxo de cancelamento.

## E2E (Playwright)

Dashboard; Demo 1 mostrando INCONSISTENCY/INVÁLIDA; optimistic com conflito; passo a passo liberando
checkpoint; comparador com disclaimer; reexecução pelo histórico.

## Verificação manual da stack

O script `examples/smoke.mjs` executa as 8 demos (e outras) contra `http://localhost:4200`, valida o stream SSE
através do Nginx e as exportações.

## Não verificado automaticamente

* Os testes do modo distribuído (duas JVMs) não rodam no Testcontainers; foram verificados manualmente
  via `examples/smoke.mjs` / `examples/distributed.mjs` com o perfil `distributed`.
* Não há testes de carga.
