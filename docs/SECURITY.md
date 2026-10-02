# Security and Privacy

This is an engineering threat model, not legal advice or a certification of GDPR/DPDP compliance. Only synthetic data is authorized in this repository.

## Trust boundaries

Browser to API; synthetic/OEM adapter to broker; broker to normalization worker; API/worker to stores; operator to deployment system; assistant tool router to tenant-scoped services. A tenant ID is taken from the authenticated JWT for HTTP requests, never from an arbitrary request body. Broker envelopes require trusted producers and topic ACLs; they are not independently authenticated tenant assertions.

## STRIDE

| Threat | Example | Current control | Remaining production control |
| --- | --- | --- | --- |
| Spoofing | Forged tenant or OEM device | JWT signature/issuer checks; OIDC audience; ownership lookup | Device/OEM mTLS gateway, short-lived connector credentials, Kafka ACLs |
| Tampering | Changed battery units or mapping code injection | Exact schema version, allowlisted fields, numeric transforms, sample checks; no executable mapping expressions | Signed schema registry changes, independent approval policy |
| Repudiation | Engineer denies a mapping change | Approval/replay/read/tool audit rows | Append-only external audit sink, restricted DB roles, audit of denials and internal accesses |
| Information disclosure | Cross-tenant VIN lookup or raw location exposure | Tenant predicates; viewer raw-data prohibition and rounded coordinates | PostgreSQL RLS, composite tenant FKs, encryption/key policies, privacy review of driver fields |
| Denial of service | Oversized JSON, bursty OEM, expensive analytics | Proxy body limit, local request guard, bounded pagination, Kafka buffer, retry timeout, projection circuit breakers, bounded read summaries | Distributed per-tenant quotas, decompression limits, admission control, sharded production read models |
| Elevation of privilege | Agent approves mappings or viewer erases data | Server method authorization; agent exposes no mutation tool; exact-VIN admin confirmation | OIDC role-mapping review, separate approval duties, signed artifact supply chain |

## Production configuration

Use `SPRING_PROFILES_ACTIVE=production`; local login then fails closed unless an explicitly local/test profile is enabled. Configure `OIDC_ISSUER`, `OIDC_AUDIENCE` and `OIDC_CLIENT_ID`. Register the web origin's `/` callback for authorization-code PKCE. Tokens need `tenant_id` and a root `roles` list containing `VIEWER`, `ENGINEER` or `ADMIN`. Map these in the IdP; do not accept user-controlled tenant claims.

Update the web CSP `connect-src` to permit the exact IdP origin for discovery/token calls. Configure the IdP's CORS and expected API audience. No client secret belongs in the SPA. The sample keeps access tokens in session storage; production can instead use a BFF with secure HttpOnly cookies and CSRF controls. Local sign-out clears workspace credentials; organizational SSO logout/session-revocation policy still needs integration.

Terminate browser TLS1.3 at a configured ingress; use a service mesh or endpoint TLS for service-to-service traffic, and mTLS for device/OEM ingress. Supply Kafka TLS/SASL, PostgreSQL TLS, Redis TLS/auth and Cassandra TLS/auth through protected configuration. The current Cassandra helper only supports its local no-auth demo endpoint and must be extended before secure production use.

Provision secrets through a vault/External Secrets integration and use workload identity; the Helm chart references a pre-existing runtime Secret and does not create one. Terraform configures KMS-backed Kubernetes/object encryption but does not deploy a vault, encrypted relational/cache cluster or device PKI. Native H2 and Compose volumes are **not automatically AES-256 encrypted**. Review images, pin release digests and sign production artifacts.

The Compose network deliberately binds published ports to loopback and enables demo credentials. Do not change its published host to `0.0.0.0` and treat it as secure production deployment.

## Erasure workflow

`POST /api/privacy/erase` requires ADMIN and an exact repeated VIN. It locks the vehicle, deletes SQL telemetry/revisions/alerts/state/outbox/registry rows, replaces VIN-based audit resources with a tenant-scoped hash, and retains an erasure request without the plain VIN. Replay and projection use the same vehicle lock and refuse removed vehicles. Re-ingestion fails ownership validation.

Response status is deliberately `LOCAL_PURGED_EXTERNAL_PENDING`. It is **not complete erasure**. Redis/Cassandra may already hold projections; Kafka raw/normalized/DLT topics, object versions, operator exports, backups and traces may contain the subject. A production coordinator must resolve a protected subject identifier, purge each sink, invalidate caches, rewrite affected Parquet partitions, remove old object versions, apply deletion suppression on restore, and obtain independent verification before completion. Hashes are pseudonymous data, not guaranteed anonymous data.

Test local negative access, missing confirmation, read-after-delete, replay-after-delete and cache repopulation races. Test every enabled external sink and restored backup in staging. Retention TTL alone does not prove timely erasure.

## Security testing

JWT/RBAC/tenant tests and both native/Docker browser suites passed. Thirteen authenticated HTTP regression checks passed per deployment, including anonymous/malformed-token denial, role restrictions, tenant isolation, query parameter handling and security headers. These bounded checks are not a full DAST scan or penetration test.

Executed npm and Python audits report zero known vulnerabilities. Trivy 0.75.0 inventoried 136 Java packages in the actual API image and reported zero known Java findings. Dependency/base-image updates removed the HIGH/CRITICAL findings from the initial API and web image scans. Final API OS findings remain: 13 MEDIUM and 16 LOW; final web image reports zero. Original and final reports are retained under `evidence/`. Infrastructure images were not included. Findings depend on the scanner database and are not a security certification.

CI includes Semgrep and Trivy definitions, but remote CI and full SAST have not been executed. Configure authenticated ZAP contexts for viewer/engineer, exclusion of destructive endpoints, token renewal, and tenant-crossing probes in an isolated synthetic environment. Review the remaining OS and infrastructure findings before any public deployment. Do not run destructive scans against a real OEM system.
