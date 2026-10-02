# Sources and Scope

- User-provided `Motorq_Hackathon_Problem_Statement.pdf`,10pages: authoritative source for this academic brief's required features, targets and submission rules. Company statistics and projections inside it are background assertions, not verified facts about the current company.
- [Apache Kafka design](https://kafka.apache.org/design/): transport, acknowledgement and delivery semantics. External storage requires cooperative idempotency/coordination; Kafka producer settings do not make all side effects exactly once.
- [pgvector official documentation](https://github.com/pgvector/pgvector): PostgreSQL vector storage and distance operators. Exact cosine search is used for this tiny static runbook corpus; no HNSW speedup is claimed.
- [Kubernetes horizontal pod autoscaling](https://kubernetes.io/docs/concepts/workloads/autoscaling/horizontal-pod-autoscale/): HPA behavior and metrics dependency. Replica configuration is not benchmark evidence.
- [Testcontainers Kafka module](https://testcontainers.com/modules/kafka/): real broker integration-test setup. Tests skipped because Docker is unavailable are not passes.
- [oidc-client-ts UserManager](https://authts.github.io/oidc-client-ts/classes/UserManager.html): authorization-code redirect/callback integration. Real identity-provider configuration still requires end-to-end verification.

No private Motorq endpoints, real vehicle-owner datasets, paid cloud accounts or undisclosed external model services are used by the native demo.
