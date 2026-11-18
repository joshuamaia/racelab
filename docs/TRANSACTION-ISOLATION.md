# Isolamento transacional: padrão SQL × PostgreSQL

## Matriz

| Fenômeno | Read Uncommitted | Read Committed | Repeatable Read | Serializable |
|---|---|---|---|---|
| Dirty Read | Possível no padrão SQL — **nunca no PostgreSQL** | Não | Não | Não |
| Non-Repeatable Read | Possível | Possível | Não | Não |
| Phantom Read | Possível | Possível | Padrão: possível · **PostgreSQL: não** (snapshot) | Não |
| Write Skew | Possível | Possível | **Possível no PostgreSQL** | Impedido (aborta com 40001) |
| Lost Update (read-modify-write) | Possível | Possível | Detectado: 40001 *could not serialize access due to concurrent update* | Detectado: 40001 |

## O que o PostgreSQL faz de fato

* **READ UNCOMMITTED = READ COMMITTED.** O PostgreSQL aceita a sintaxe, mas o MVCC nunca expõe versões de
  linha não confirmadas. O RaceLab mostra `effectiveIsolationLevel = READ_COMMITTED` e o probe reporta
  "prevented" — **não fabricamos dirty reads**.
* **READ COMMITTED**: cada *statement* vê um snapshot novo. Um `UPDATE` que espera por um lock reavalia a linha
  com a versão confirmada mais recente (é por isso que o lost update acontece: o `UPDATE … SET balance = 200`
  absoluto sobrescreve; e é por isso que `WHERE balance >= :amount` funciona).
* **REPEATABLE READ** é *snapshot isolation*: o snapshot é tirado no **primeiro statement** da transação.
  Impede non-repeatable e phantom reads nas leituras do snapshot. Se a transação tentar atualizar uma linha
  alterada após o snapshot, recebe **40001**. Não detecta conflitos entre linhas **diferentes** → write skew.
* **SERIALIZABLE** (SSI): rastreia dependências leitura/escrita e aborta estruturas perigosas com **40001**.
  A aplicação **precisa** repetir a transação.

## Experimentos do Isolation Lab

| Probe | Coreografia (TurnSequencer) | RC | RR | SER |
|---|---|---|---|---|
| Dirty read | W: UPDATE sem commit → R: SELECT → W: ROLLBACK | prevenido | prevenido | prevenido |
| Non-repeatable read | R: SELECT → W: UPDATE + COMMIT → R: SELECT | **reproduzido** | prevenido | prevenido |
| Phantom read | R: COUNT → W: INSERT + COMMIT → R: COUNT | **reproduzido** | prevenido | prevenido |
| Write skew (`write-skew`) | ambos COUNT → ambos UPDATE linhas diferentes | **reproduzido** | **reproduzido** | 40001 |

Esses resultados são verificados por `IsolationLevelsIT` e `ConcurrencyScenariosIT` contra PostgreSQL 17 real.

## Armadilha: advisory lock + REPEATABLE READ

Sob REPEATABLE READ o snapshot nasce no primeiro statement. Se o advisory lock for adquirido *depois* desse
statement, a transação que esperou o lock ainda lê o snapshot antigo. No cenário de write skew a estratégia
`ADVISORY_LOCK` roda em READ COMMITTED de propósito — o lock deve preceder o snapshot.

## Erros relevantes

| SQLSTATE | Significado | Tratamento no RaceLab |
|---|---|---|
| 40P01 | deadlock detected | `DEADLOCK_DETECTED` (observado), rollback, retry limitado opcional, grafo do DETAIL |
| 40001 | serialization failure | `SERIALIZATION_FAILURE`, rollback, retry opcional |
| 23505 | unique violation | rollback esperado (constraint funcionando) |
| 55P03 | lock not available (`lock_timeout`) | `LOCK_TIMEOUT` |
| 57014 | query canceled | `CANCELLED` (cancelamento) ou `TRANSACTION_TIMEOUT` (`statement_timeout`) |
| 25P03 | idle in transaction timeout | `TRANSACTION_TIMEOUT` |
