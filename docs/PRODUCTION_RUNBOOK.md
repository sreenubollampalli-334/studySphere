# StudySphere native production runbook

## Release

The reference topology uses one Linux host with Java 21, systemd, Caddy, a native MySQL 8 service and a persistent resource directory. The application listens on loopback. Caddy serves the Vite build, terminates TLS, and proxies the API and WebSocket on the same origin. Keep MySQL, the application port, and any Qdrant administrative ports private. Use S3-compatible storage, Qdrant, SMTP, and AI providers when the approved deployment calls for those features.

A version tag starting with `v` runs both CI jobs and packages their successful outputs in the `studysphere-native` artifact. Downloads contain the archive and its SHA-256 manifest. An operator promotes the checked bundle to the approved host. For a local build from the repository root, run:

```bash
(cd backend && mvn -B -ntp verify)
(cd frontend && pnpm install --frozen-lockfile && pnpm lint && pnpm test && pnpm build)
bash ops/package-release.sh /tmp/studysphere-release
```

1. Verify `sha256sum -c studysphere-native.tar.gz.sha256` inside the downloaded artifact directory. Extract into a new release directory under `/srv/studysphere/releases`. The archive has a top-level `studysphere/` directory. Make the extracted release readable to the backend and Caddy services, with write access restricted to operators.
2. Create a dedicated system user and group named `studysphere`. Install `ops/studysphere.service` under `/etc/systemd/system`. Configure `/etc/studysphere/backend.env` as root-owned mode 0600. Use the bundle's `.env.example` to identify variables. Supply the database password and any enabled provider credentials from the deployment secret store. Set `STUDYSPHERE_FRONTEND_ORIGIN` to the public HTTPS origin and keep secure cookies enabled. For filesystem resources, set `STUDYSPHERE_STORAGE_DIRECTORY=/var/lib/studysphere/resources`. Additional writable locations require an explicit systemd policy update.
3. Install `ops/Caddyfile` as the Caddy configuration and set `STUDYSPHERE_SITE` to the public DNS name in Caddy's service environment. Point DNS to the host. Allow HTTPS and the HTTP/TLS certificate challenge ports through the firewall. Validate with `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` in an environment where the hostname variable is set.
4. Schedule a maintenance window for schema-changing upgrades. Stop the application, take a coordinated snapshot, then point `/srv/studysphere/current` at the new release's `studysphere/` directory. Run `systemctl daemon-reload` and `systemctl enable --now studysphere`. Reload Caddy after configuration changes. Flyway validates existing migration checksums and applies pending migrations at startup.
5. Verify that the public home page renders, `GET /actuator/health` returns UP, and an unauthenticated `GET /api/v1/me` returns 401. Use a test user to sign in over HTTPS, save a task, test private resource access with a second user, and exercise WebSockets and enabled provider integrations. Run `node ops/load-smoke.mjs https://your-approved-staging-host` with the actual staging origin. Record the bundle checksum, installed version, migration version, and observed smoke results.

For an application-only failure, return the release pointer to the prior build once its compatibility with the applied schema is confirmed. Otherwise, restore the matching pre-change snapshot to a fresh database and resource location before reverting the release. Prefer a forward correction for a migrated production database with new user writes. Obtain explicit incident approval before any recovery that would discard writes.

## Administrator access

Register a dedicated operator account and verify its email through the normal flow. An authorized database operator grants `ADMIN` by adding the account's user ID and the `ADMIN` role ID to `user_roles`. Verify the account ID, email, and active status before granting access. Record the grant in the deployment audit record. Use a separate student account for ordinary study activity. Remove the `ADMIN` mapping when the operator's access ends.

Check `/admin` with a student account and with the operator account. The student receives a denial and the operator sees the moderation queue. Use a second test account to submit a community report. Review the report, confirm the audit entry, apply a temporary restriction, confirm the test account's existing session is rejected, and lift the restriction. Confirm a new sign-in succeeds.

## Backup and restore

Keep snapshots encrypted and access-controlled off the application host. Use a backup account that can read the application schema and a separate restore account authorized only for the disposable target schema or an isolated server. Provision client authentication with `mysql_config_editor` under the operator account, and export `MYSQL_LOGIN_PATH` for the matching profile. The helper uses TCP and defaults to `127.0.0.1:3306`. Set `MYSQL_HOST` and `MYSQL_TCP_PORT` when needed. Use a MySQL 8 client and GNU-compatible `tar` and `sha256sum`.

1. Stop StudySphere for the duration of a coordinated snapshot, or otherwise quiesce every writer and prevent DDL. The SQL dump uses a consistent transaction. Quiescence also keeps database references aligned with file and external storage snapshots.
2. Run the following with an existing snapshot parent directory and the actual schema and filesystem resource directory. Use a unique snapshot name and retain the corresponding application release checksum. An interrupted snapshot will fail restore verification unless all files and their manifest were finished.

```bash
MYSQL_LOGIN_PATH=studysphere-backup bash ops/data-snapshot.sh backup studysphere \
    /srv/backups/studysphere/2026-11-25T000000Z /var/lib/studysphere/resources
```

3. For S3-compatible storage, omit the filesystem argument and preserve the private bucket's object versions and keys in a separately protected backup or replicated bucket. For Qdrant, preserve a collection snapshot with its configuration and embedding model version, or reindex restored source documents following the application indexing flow. Record which external snapshots match the database snapshot. Keep secrets and signing/provider configuration in the secret store.
4. On a restricted restore host, restore from authenticated backup storage into a fresh schema and filesystem location. The helper checks backup files against their manifest, creates a fresh schema with a `studysphere_restore_` prefix, and refuses an existing database. Use snapshots created by the helper or otherwise authenticated and operator-verified. Restore the matching external object or vector snapshot to a separate staging location before enabling its application feature.

```bash
MYSQL_LOGIN_PATH=studysphere-restore bash ops/data-snapshot.sh restore studysphere_restore_rehearsal \
    /srv/backups/studysphere/2026-11-25T000000Z /var/lib/studysphere/resources-rehearsal
```

5. Point an isolated app at the recovery schema and resource location. Start the bundle version referenced by the backup and confirm Flyway validation and health. Disable outbound notification and AI integrations for rehearsals unless they are isolated test providers. Check sample owner and non-owner access, file integrity, and recent data counts. Run the load smoke test, record elapsed recovery time and the age of recovered data, and retain the rehearsal results. Promotion of a recovered database is an incident decision.

The CI rehearsal takes a native MySQL snapshot, restores it to a fresh schema, compares Flyway migration counts and a file fixture, starts the packaged backend on the restored database, and applies the HTTP smoke thresholds. It exercises the local production-shaped path without requiring a deployment account or provider secrets.

## Monitoring and incidents

Collect systemd application and Caddy logs in the approved restricted monitor. Read the current backend logs with `journalctl -u studysphere` and proxy logs with `journalctl -u caddy`. Caddy removes the URI from stored access records to keep search phrases and resource identifiers out of those logs. Keep request body and authorization logging disabled. Restrict log access and apply the approved retention schedule.

- Probe HTTPS health every minute. Page after three consecutive failures. Check DNS and TLS, app process restart history, the DB connection, and disk capacity before restoring service.
- Track proxy 5xx rates and latency. Start with alerts for over 2% 5xx for five minutes at meaningful request volume or p95 over one second for ten minutes. Review startup, upload, query, and provider errors using the logs. Exercise staging with the bounded read-only load smoke before changing these thresholds.
- Track backup freshness, transfer failures, and recoverability. The proposed starting cadence is daily backups and monthly isolated restores, with a 24-hour RPO and a four-hour RTO. Validate these targets with measured data sizes and restore times. Escalate missing snapshots or checksum failures.
- For abuse or suspected unauthorized access, preserve the relevant audit records and restricted logs, apply moderation or account restrictions through the admin controls, and record scope and actions in the incident record. Credentials exposed outside the secret store must be revoked and replaced with the owning provider.

Host selection, DNS, certificates, external service recovery, alert destinations, backup retention, and measured production objectives require deployment-owner configuration and validation before launch.
