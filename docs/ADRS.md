# Architecture Decision Records

## ADR 001: Deterministic contracts before adaptive interpretation

**Status:** adopted. Unknown schemas, invalid types and impossible units are quarantined. JSON-pointer mappings have explicit numeric transforms and canonical required fields. Approval must pass sample validation and requires an engineer/admin action.

**Alternatives:** permissive field-name matching or an LLM applying mappings autonomously. These can silently turn incorrect units into plausible values.

**Consequences:** initial data availability is lower during a contract change, but bad data does not become trusted output. A human still verifies source semantics; sample tests are not an OEM specification. ML is advisory, never an authorization decision.

## ADR 002: At-least-once transport with atomic relational effects

**Status:** adopted for the prototype. Kafka acknowledges durably; normalizer transactions write raw status, revision, alert, current state and an outbox. Re-delivery uses unique tenant/event keys. A durable replay cursor commits with its page.

**Alternatives:** Kafka transactions with all state inside Kafka Streams, or distributed transactions across every storage engine. The former needs a redesigned authoritative store; the latter is complex and increases coupling.

**Consequences:** effective idempotency for represented effects, not universal exactly-once delivery. Optional sinks must remain idempotent. Retry/DLT exhaustion needs reconciliation; a DLT record is not successful processing. PostgreSQL throughput becomes a scaling limit to address before 100K/s.

## ADR 003: Normalize the control plane, denormalize read state

**Status:** adopted. Separate fleet, driver, manufacturer and model entities. Keep ownership/approval/audit transactional. Latest state and TOTAL/MINUTE/DAY summaries are deliberate denormalized read projections, maintained atomically with the local ledger. Cassandra and Parquet are appropriate telemetry-history targets; Redis is disposable.

**Alternatives:** a single SQL database for all production telemetry or a single eventually consistent store for approvals and ownership.

**Consequences:** more operational complexity in the deployed polyglot configuration, but data has explicit consistency and retention boundaries. The native demo keeps an SQL event ledger for reproducibility and is not the final high-throughput architecture.

## ADR 004: Deterministic agent baseline and exact vector retrieval

**Status:** adopted. A read-only question router invokes bounded tools and cites their results through an audit trail. Static runbooks use normalized hashed lexical vectors. Four documents use exact cosine distance, with pgvector in PostgreSQL or an equivalent local fallback.

**Alternatives:** a hosted LLM with general SQL tools, dense embeddings, or an HNSW index for a tiny corpus.

**Consequences:** no external model credentials, no telemetry exfiltration, reproducible answers and no autonomous mutation. It is less flexible than an LLM. Lexical collisions and limited language coverage are known limitations. Add dense retrieval and agent evaluation only when evidence justifies the cost and complexity.

## ADR 005: One deployable prototype, portable production boundaries

**Status:** prototype adopted; distributed evolution proposed. Java core logic, SQL, Kafka, Redis, Cassandra, Parquet and standard OIDC avoid application dependence on one cloud SDK. Compose is the reproducible development stack; Helm and an AWS infrastructure foundation define deployment surfaces.

**Alternatives:** many independently deployed microservices immediately, or exclusively proprietary cloud data services.

**Consequences:** faster local debugging and a coherent demo. Not full compliance with the brief's cloud-native microservices requirement. Separate workers, publish real CI evidence, configure multi-zone data services and prove a second-cloud deployment before claiming portability has been validated.
