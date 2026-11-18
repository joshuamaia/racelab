# Concorrência distribuída

## Duas JVMs independentes

```bash
docker compose --profile distributed up --build -d
```

Sobe `racelab-backend` (primary) e `racelab-backend-secondary`, dois processos Java distintos, mesmo
PostgreSQL e Redis. Com `topology = DISTRIBUTED`, os workers pares rodam no primary e os ímpares no secondary.
O BFF roteia chamadas por `X-RaceLab-Instance: primary|secondary` (ou `?instance=` para SSE).

Resultado medido no cenário `bank-withdrawal` (2 saques de R$ 800, saldo R$ 1.000):

| Estratégia | Protege entre JVMs? | Por quê |
|---|---|---|
| NONE | ❌ | sem controle |
| JAVA_SYNCHRONIZED | ❌ | cada JVM tem seu próprio monitor |
| REENTRANT_LOCK | ❌ | o objeto de lock vive no heap de uma JVM |
| ADVISORY_LOCK | ✅ | o lock é mantido pelo PostgreSQL, compartilhado por todas as sessões |
| PESSIMISTIC_LOCKING | ✅ | lock de linha no banco |
| ATOMIC_UPDATE | ✅ | o banco avalia a condição na versão mais recente |
| REDIS_LOCK | ✅ (enquanto o lease for válido) | lock externo compartilhado |

## Redis lock

* Aquisição: `SET key owner NX PX ttl` — `owner` é único por tentativa (`txId:uuid`).
* Liberação segura: script Lua *compare-and-delete* — quem perdeu o lease nunca apaga o lock do novo dono.
* Fencing token: `INCR racelab:fence:<key>` a cada aquisição (monotônico).
* O lock envolve a transação **inteira, incluindo o COMMIT**.

### Um lock Redis sozinho não garante consistência

O experimento `lock-expiration` demonstra: o cliente 1 adquire o lease e "pausa" (GC, I/O lento — falha
modelada e rotulada no timeline) por mais que o TTL. O lease **expira de verdade no Redis**, o cliente 2 o
adquire, lê, grava e confirma; o cliente 1 acorda acreditando ainda ser dono e grava o valor obsoleto.
Resultado: dois donos, lost update. A liberação do cliente 1 é recusada (o lock já é de outro).

Outros cenários de falha que um lease não cobre: pausas do processo, saltos de relógio, failover do Redis
com replicação assíncrona, partições de rede.

### Fencing tokens

Com `FENCING_TOKEN`, cada escrita carrega o token do lease e o banco a aceita somente se ele for maior que o
último visto:

```sql
UPDATE account SET balance = ?, last_fencing_token = ?
 WHERE id = ? AND last_fencing_token < ?
```

A escrita do dono obsoleto afeta 0 linhas e é rejeitada (`LOCK_NOT_ACQUIRED`). Fencing só funciona quando o
recurso protegido consegue validar o token.

## Idempotência

* Chave persistida com `UNIQUE (experiment_id, idempotency_key)`, hash do payload e resposta.
* O retry faz `INSERT … ON CONFLICT DO NOTHING`: **espera** no índice único enquanto a primeira requisição
  não termina (espera observada em `pg_blocking_pids`), depois lê o registro e devolve a resposta original.
* Reuso da chave com payload diferente → rejeição (422).
* Só `UNIQUE_CONSTRAINT`: impede a cobrança dupla, mas o cliente recebe erro em vez da resposta original.

## Transactional Outbox

```
Fluxo inseguro (dual write)          Outbox
1. INSERT pedido; COMMIT             1. BEGIN
2. publicar evento   ← crash aqui    2. INSERT pedido
                                     3. INSERT outbox_event
                                     4. COMMIT
                                     5. relay: SELECT … FOR UPDATE SKIP LOCKED → publica → marca PUBLISHED
                                     6. consumidor: INSERT processed_event ON CONFLICT DO NOTHING → efeito
```

| Falha injetada | NONE (dual write) | OUTBOX | OUTBOX_NAIVE_CONSUMER |
|---|---|---|---|
| `CRASH_AFTER_COMMIT` | pedido sem evento (perdido) | evento seguro na tabela, relay publica | idem |
| `CRASH_BEFORE_COMMIT` | evento fantasma de pedido inexistente | — | — |
| `RELAY_CRASH_BEFORE_ACK` | — | entrega duplicada, **processada uma vez** | **efeito duplicado** |

**Outbox não implica exactly-once**: a entrega é at-least-once; a deduplicação pertence ao consumidor.
Mensagens vão para Redis Streams (`racelab:outbox:<experimento>`) quando o Redis está disponível, ou para um
log em memória (o nome do broker aparece no evento `DATA_PREPARED`).

## Processamento concorrente de mensagens

`order-processing`: sem claim, dois workers pegam o mesmo pedido mais antigo e enviam duas vezes.
`ATOMIC_CLAIM` (`UPDATE … WHERE status='PENDING'`), `SKIP_LOCKED` (cada worker pega uma linha diferente sem
esperar) e `IDEMPOTENCY_KEY` (consumidor idempotente) preservam "enviado exatamente uma vez".
