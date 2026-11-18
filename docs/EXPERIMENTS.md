# Experimentos

Cada entrada do catálogo (`GET /api/catalog`) combina um **cenário executável** (`GET /api/scenarios`), uma
**estratégia** e uma **configuração**. Todos executam transações reais no PostgreSQL; o resultado é calculado a
partir do estado final lido do banco e das operações efetivamente confirmadas.

## Cenários executáveis

| Cenário | Negócio | Invariante | Estratégias |
|---|---|---|---|
| `bank-withdrawal` | Conta R$ 1.000, saques concorrentes de R$ 800 | inicial − saques confirmados = final ∧ saques ≤ saldo inicial | NONE, OPTIMISTIC_LOCKING, PESSIMISTIC_LOCKING, ATOMIC_UPDATE, SERIALIZABLE, JAVA_SYNCHRONIZED, REENTRANT_LOCK, ADVISORY_LOCK, REDIS_LOCK (local ou **duas JVMs**) |
| `inventory` | Estoque 10, compras de 7 | inicial − vendido = final ∧ estoque ≥ 0 | NONE, ATOMIC_UNGUARDED, OPTIMISTIC_LOCKING (JDBC), PESSIMISTIC_LOCKING, ATOMIC_UPDATE, SERIALIZABLE |
| `seat-reservation` | Mesmo assento, vários clientes | reservas por assento ≤ 1 | NONE, UNIQUE_CONSTRAINT, PESSIMISTIC_LOCKING, ATOMIC_UPDATE |
| `duplicate-payment` | Timeout do cliente + retry | pagamentos por chave ≤ 1 | NONE, UNIQUE_CONSTRAINT, IDEMPOTENCY_KEY |
| `deadlock` | Transferências em ordem inversa | soma conservada ∧ transferências aplicadas | NONE, LOCK_ORDERING |
| `write-skew` | Dois médicos de plantão | médicos de plantão ≥ 1 | NONE (isolamento configurável), SERIALIZABLE, PESSIMISTIC_LOCKING, ADVISORY_LOCK |
| `order-processing` | Workers consumindo pedidos | cada pedido enviado no máximo uma vez ∧ todos processados | NONE, ATOMIC_CLAIM, SKIP_LOCKED, IDEMPOTENCY_KEY |
| `dirty-read`, `non-repeatable-read`, `phantom-read`, `isolation-probe` | Leituras de saldo/movimentações | observação (não é invariante de negócio) | ISOLATION_LEVEL |
| `lock-expiration` | Lease Redis expira durante o processamento | inicial − saques = final | REDIS_LOCK, FENCING_TOKEN |
| `outbox` | Pedido + evento para o faturamento | todo pedido anunciado ∧ nenhum evento fantasma ∧ cada evento processado ≤ 1× | NONE (dual write), OUTBOX, OUTBOX_NAIVE_CONSUMER |

## Catálogo (33 experimentos)

| Categoria | Experimentos |
|---|---|
| **Concorrência** | Race Condition · Lost Update · Lost Update + Optimistic · Deadlock · Check-Then-Act · Double Booking · Overselling · Duplicidade de pagamentos · Violação de unicidade · Write Skew · Contenção de recursos |
| **Isolamento** | Dirty Read · Non-Repeatable Read · Phantom Read · Read Committed · Repeatable Read · Serializable · Read Uncommitted |
| **Locking** | Sem controle · Optimistic · Pessimistic · Atomic Update · synchronized · ReentrantLock · Advisory Lock · Redis Lock |
| **Distribuído** | Idempotência · Retry com risco de duplicidade · Distributed Lock · Processamento concorrente de mensagens · Transactional Outbox · Falha parcial entre serviços · Expiração de lock · Duplicidade de eventos · JVMs independentes |

Cada entrada traz nome, descrição, categoria, dificuldade, problema, cenário de negócio, configuração,
resultado esperado, estratégias disponíveis, explicação técnica e recomendações; o resultado obtido, o código
Java/SQL (extraído do código que roda) e as recomendações calculadas acompanham cada execução.

## Resultados medidos (execução real, PostgreSQL 17, esta máquina)

| Demo | Resultado observado |
|---|---|
| 1 Lost Update | 2 saques confirmados (R$ 1.600), saldo R$ 200, esperado −600 → **INCONSISTENTE** |
| 2 Optimistic | 1 commit, 1 `OPTIMISTIC_LOCK_CONFLICT` (UPDATE … WHERE version afetou 0 linhas) → consistente |
| 3 Deadlock | SQLSTATE **40P01** detectado pelo PostgreSQL, vítima revertida, retry confirma; grafo do ciclo extraído do DETAIL |
| 4 Estoque | NONE: 14 unidades vendidas de 10 (estoque 3); ATOMIC_UNGUARDED: estoque −4; ATOMIC_UPDATE: 1 compra rejeitada, estoque 3 |
| 5 Idempotência | O retry espera no índice único (`LOCK_WAITING` observado), lê o registro e recebe a resposta original; 1 pagamento |
| 6 JVMs | `synchronized`/`ReentrantLock` em primary+secondary → lost update; ADVISORY/PESSIMISTIC/ATOMIC/REDIS → consistentes |
| 7 Write Skew | REPEATABLE READ: ninguém de plantão; SERIALIZABLE: **40001**, regra preservada |
| 8 Outbox | Relay cai após publicar: 4 entregas (1 duplicada), 3 faturas — consumidor idempotente |

Os números dependem de hardware, configuração do banco e carga — rode-os você mesmo.

## Eventos e estados

Ver [API-CONTRACT.md](API-CONTRACT.md) para todos os tipos de evento. `source = DATABASE_OBSERVED` marca o que
foi lido do PostgreSQL (SQLSTATE, DETAIL de deadlock, `pg_blocking_pids`); `INSTRUMENTED` marca o que o código
da aplicação registrou. Falhas injetadas (crash do processo no outbox) aparecem como `FAULT_INJECTED` e nunca
como erro de banco simulado.

## Consistency Validator

Invariantes (`Invariant`: EQUALS / AT_MOST / AT_LEAST) são avaliadas sobre valores lidos do banco após o
experimento. Cada violação traz regra, esperado, encontrado, diferença, transações envolvidas, severidade,
causa provável e estratégias recomendadas. Rollbacks esperados (deadlock, 40001, conflito de versão, 23505,
rejeições de negócio) **não** são inconsistências.

## Recommendations Engine

Regras determinísticas acionadas pelo que aconteceu (violações, SQLSTATEs, tempo de espera por lock,
conflitos sem retry, topologia distribuída…). Cada recomendação tem justificativa, benefícios e trade-offs.
Nenhum serviço externo de IA é usado.

## Como adicionar um cenário

1. Crie um bean que estenda `ScenarioSupport` (ou implemente `Experiment`) em `dev.racelab.scenario.<nome>`.
2. Faça o seed a partir de templates com `experiment_id = ctx.id()`.
3. Monte `WorkerSpec`s e use `coordinator.execute(rt, spec, tx -> …)` com as fases `tx.read/validate/sync/write/lock`.
4. Leia o estado final e retorne um `ScenarioReport` com `Invariant`s avaliadas pelo `ConsistencyValidator`.
5. Marque o código com `// @snippet:start <cenário> <ESTRATÉGIAS|*> <java|sql> "título"` … `// @snippet:end`.
6. Adicione presets ao `PresetCatalog` e um teste `*IT` com Testcontainers.
