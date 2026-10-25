# Phase 0 legacy reader — `ls@migration`: build, dependencies and infrastructure

Read on 2026-10-17 against `legacy/ls` (`git rev-parse HEAD` = `ca1b0d0`, 2025-11-16), with chapter 10 §1 and
12d as the map. Status labels: **VERIFIED** (read in the code or observed in a build), **DIFFERS** (code or build
says otherwise), **NOT FOUND**, **NOT CHECKED** (would need `logs/`, `api/`, `.env*`, `local-setup.sh`, a YAML
value, another branch, or the production server: all deleted, redacted or absent here by rule). "(inferred)" =
follows from documented tool behaviour. Lifecycle dates are **as of training knowledge** (cutoff June 2026) and
are marked *(TK)*. No secret, personal datum, price or configuration value is reproduced; the sanitised checkout
differs from `ca1b0d0` (`git status`: 25 tracked files deleted, 8 files redacted locally: 6 YAML, `README.md`, `scripts/backup.sh`), and
line numbers below refer to the files as they stand here (only values changed, so line numbers match the commit).

## 1. What was read (paths, all `ls@migration:`)

* Build: `pom.xml` (all 202 lines), `.mvn/wrapper/maven-wrapper.properties`, `mvnw` (mode only), `Dockerfile`,
  `Dockerfile.local`; the build itself (§2 (a)); `mvn dependency:list` and `dependency:tree` output.
* Infrastructure: `docker-compose.yml`, `docker-compose.local.yaml`, `nginx/nginx.conf`, `nginx/conf.d/app-https.conf`,
  `nginx/conf.d/logistica.conf`, `scripts/{deploy,ssl-setup,backup,monitoring,server-setup}.sh` (whole),
  `scripts/fix-actuator.sh` (head and grep), `README.md` (whole), `.gitignore`.
* Configuration: key names only of `src/main/resources/application.yml`, `application.prod.yml`, `application-dev.yml`
  (lines 17-19, 69-79), `application.local.yml` (71, 75-76), `src/test/resources/application-test.yml`,
  `application-h2.yml`; `src/main/resources/logback.xml`; `init.sql` statement types only.
* Code needed for §1/§6 of 12d: `configuration/AwsMailConfig.java`, `configuration/security/WebSecurityConfig.java`,
  `configuration/constant/{PathConstants,PatternConstants}.java`, `business/util/LinkUtil.java`, `api/controller/
  {Order,Cms}Controller.java` (55-59, 79-80, 120-130), `api/error/ErrorController.java:25-35`,
  `configuration/RequestResponseLoggingFilter.java:30-50`, `configuration/security/JwtService.java:35-45`,
  `configuration/{CacheConfig,MvcConfig}.java`, `MonoApplication.java`, `web/WebController.java:1-60`,
  `templates/tracking-state.html:48`; tests `MonoApplicationTests`, `NonRegressionTests`, `IntegrationTests`,
  `HttpTestClient.java:62`, `CmsTestClient.java:30-36` (values masked).
* Git (read-only, no `show`/`log -p`/checkout): `git branch -r`, `git for-each-ref`, `git log --oneline -15`,
  `git log -3 --date=short`, `git status --short`, `git config remote.origin.fetch`.
* Not opened, by rule: `logs/`, `api/`, `.env*`, `local-setup.sh`, any YAML value, other branches.

## 2. Verified facts

### 2.1 Build (task (a))

| Claim / observation | Status | Evidence |
|---|---|---|
| The source builds locally with the wrapper | **VERIFIED** | `sh ./mvnw -B -q -DskipTests package` (the wrapper files are mode 644 in this checkout, so `./mvnw` needs `sh`): exit 0, **38 s** wall clock including the download of Apache Maven 3.9.5 (`.mvn/wrapper/maven-wrapper.properties:1`) and of 141 MB of dependencies through the session proxy; output `target/mono-0.0.2-SNAPSHOT.jar`, **81.6 MB, 104 jars** in `BOOT-INF/lib`. Second run `mvn clean compile`: 7.3 s, 149 source files. |
| Compiles under the installed JDK 21 although the pom targets Java 17 | **VERIFIED** | JDK 21.0.12.1 (Ubuntu), Maven 3.9.11 at `/opt/maven` also works. javac ran with `--release 17` (`maven.compiler.release` = 17 from the Boot parent; class major version 61). Lombok 1.18.30 supports JDK 21 *(TK)*. Only warnings: duplicate `jackson-datatype-jsr310` declaration (`pom.xml:123`), a Lombok `@Builder` warning at `business/model/Spedizione.java:20`, unchecked operations in `JwtService.java`. |
| The first error | n/a | none (the only failure in this session was a missing `/usr/bin/time` in my own harness, not the build) |
| Tests exist and are disabled (12a SEC-21, 10 §1) | **VERIFIED** | Three test classes compile (`target/test-classes`): `MonoApplicationTests` (`@ActiveProfiles("test")`, needs a local MySQL), `NonRegressionTests` (`@ActiveProfiles("h2")`, one test, seeds `init.sql`), `IntegrationTests` with `@Disabled` at `:110`. `Dockerfile:17` builds with `-DskipTests`; no surefire configuration; no CI (`.github/` NOT FOUND). Tests were not run here (they start a Spring context against a database and construct the SES client). The test client passes `(uri, token)` into `postRestCall(..., String token, String uri, ...)` (`CmsTestClient.java:34` → `HttpTestClient.java:62`). Fixtures hold a real-looking e-mail and phone (`IntegrationTests.java:74-75, 84-85, 95-96`; `NonRegressionTests.java:73`) and a hard-coded staff login (`CmsTestClient.java:33`): never copy them. |
| Starts locally | **NOT CHECKED** | Not attempted by rule: needs MySQL (`application.yml:17-19` keys) and builds the SES client at start-up (`AwsMailConfig.java:20-26`). No Docker daemon runs in this session (`docker info`: socket missing), so Compose cannot be used here either. |

### 2.2 Dependencies (task (b)) — declared in `pom.xml`, resolved by `dependency:tree`

| Component | Resolved version (pom line) | Support status *(TK)* |
|---|---|---|
| Spring Boot parent | 3.2.2 (8) | 3.2.x OSS support ended **2024-12-31**, commercial 2026-02-28. Current lines: 3.5.x (OSS to mid-2026) and 4.0.x (Nov 2025). |
| Spring Framework / Security / Data JPA | 6.1.3 / 6.2.1 / 3.2.2 | 6.1 OSS ended mid-2025; 6.2.1 is 10+ patches behind (CVE-2024-22234/22257, CVE-2025-22228 fixed later). Framework: CVE-2024-22243/22259/22262 (`UriComponentsBuilder`, not used here), CVE-2024-38809/38820, CVE-2025-41234 (reflected file download via `Content-Disposition` — relevant to `WebController.downloadReport`, which builds the filename from the e-mail address). |
| Tomcat embed | 10.1.18 | 10.1 line still maintained; 10.1.18 (Jan 2024) misses ~15 later CVEs (CVE-2024-24549, -23672, -34750, -50379/56337, CVE-2025-24813, -31650, -48988, -48989 "MadeYouReset", -52520, -53506); the RCE ones need a writable default servlet (not the case); the HTTP/2 ones are reachable only through nginx `http2` on 443 → upstream HTTP/1.1, so mostly mitigated (inferred). |
| Hibernate ORM | 6.4.1.Final | 6.4 line unsupported; `MySQL8Dialect` deprecated. 6.6 / 7.x current. |
| Thymeleaf | 3.1.2.RELEASE | Maintained line (3.1.3 current); fine. |
| Jackson | core/databind/annotations/jsonSchema **2.15.2** pinned (93-112); Boot-managed modules 2.15.3; `jackson-datatype-jsr310` declared twice, **2.16.1 wins** (113-117, 123-127; Maven prints the "must be unique" warning) | Mixed minor versions on one classpath; 2.15 is out of its support window; no critical CVE known for 2.15.2+ (CVE-2025-52999 is < 2.15.0). `jackson-module-jsonSchema` drags `javax.validation:validation-api:1.1.0.Final` (2013) into runtime; it is only used by the tests' schema writer (`NonRegressionTests.java:56-57`). |
| jjwt | 0.11.5 (53-66) | Old API, no known CVE; 0.12.x/0.13.x current. Retired anyway (no JWT in the new app). |
| **AWS SDK for Java v1** | `aws-java-sdk-ses` 1.12.341 (69-73) + `s3/kms/ec2/cloudformation` **1.11.792** via Spring Cloud AWS | v1 in maintenance since 2024-07-31, **end of support 2025-12-31** — already past. Pulls `ion-java` 1.0.2 (CVE-2024-21634), `joda-time` 2.8.1. The four 1.11.792 jars add ~8.3 MB to the image for nothing. |
| **Spring Cloud AWS** | `spring-cloud-starter-aws` 2.2.6.RELEASE (74-78) | Built for Boot 2.x / `javax`; EOL. Used only for `SimpleEmailServiceJavaMailSender` (`AwsMailConfig.java:10, 29-31`). Successor: `io.awspring.cloud` 3.x with SDK v2. |
| `javax.mail-api` 1.6.2 (119-122) next to `jakarta.mail` 2.0.2 (Angus) | both on the classpath | Legacy namespace; only there for Spring Cloud AWS 2. |
| MySQL Connector/J | 8.3.0 (Boot-managed) | Acceptable (9.x current). HikariCP 5.0.1, SnakeYAML 2.2, Lombok 1.18.30, Logback 1.4.14 (CVE-2024-12798/12801 need config control): old but acceptable. Lombok 1.18.30 will not compile on JDK 25 *(TK: needs ≥ 1.18.40)*. |
| PDF stack | PDFBox 2.0.24 (132-136), `flying-saucer-pdf` 9.1.22 (137-141), **`com.lowagie:itext` 2.1.7** (142-146) | iText 2.1.7 (2009) abandoned (CVE-2017-9096 XXE applicability uncertain); two BouncyCastle copies ship: `org.bouncycastle:bcprov-jdk14:1.64` (CVE-2023-33201, CVE-2024-29857/30171/30172) and `bouncycastle:bcprov-jdk14:138` (2007). Replace with `flying-saucer-pdf-openpdf` / OpenPDF (10 §1 reference stack). PDFBox 2.0 line is maintained; 2.0.24 is 2021. |
| Tests | spring-boot-starter-test 3.2.2, rest-assured 5.3.0/5.5.0 mix, hamcrest 2.2, H2 2.2.224 | fine for what they are. |
| Not present | — | no `spring-boot-starter-actuator`, no Flyway/Liquibase, no `caffeine` (although `application.prod.yml:34-37` configures it), no enforcer plugin, no plugin version pinning beyond the parent (`pom.xml:185-200`). |

### 2.3 12d §1 — production architecture as the repo describes it

| Claim (12d) | Status | Evidence |
|---|---|---|
| §1.1/§1.3 topology: Contabo VPS, Ubuntu 20.04+, `/opt/logistica-sanchez`, compose project `logisticasanchez`; three containers with fixed names, images `mysql:8.0.35`, `logistica-sanchez:latest`, `nginx:alpine` (unpinned); ports 127.0.0.1:3306/8080/5005, 0.0.0.0:80/443; volumes; networks `backend`/`frontend`; app limits 2 CPU/1536 MB; json-file log caps; mysql healthcheck; **no app healthcheck** | **VERIFIED** | `scripts/server-setup.sh:10, 135`; `README.md:6`; `scripts/ssl-setup.sh:10, 50`; `docker-compose.yml:5-39, 42-115, 118-139, 141-159` (all cited line numbers match) |
| §1.3 image build: multi-stage `maven:3.9-eclipse-temurin-17-alpine` → `eclipse-temurin:17-jre-alpine`, `-DskipTests`, exploded jar, non-root `spring` uid 1001, `TZ=Europe/Rome`, fonts, HEALTHCHECK commented out, `sh -c` entrypoint | **VERIFIED** | `Dockerfile:2, 11, 17-20, 23, 26-31, 34-36, 39-40, 46-52, 62-63, 66, 71-73, 76` |
| §1.3 compose project names differ (`-p logisticasanchez` in ssl/fix scripts; plain `docker-compose` in deploy and README) | **VERIFIED** | `ssl-setup.sh:10, 50`; `fix-actuator.sh:10`; `deploy.sh:85, 108, 147, 151`; `README.md:39` |
| §1.3 port 5005 mapped but no JDWP agent | **VERIFIED** | `docker-compose.yml:94` vs `JAVA_OPTS` at `:58-65` and `Dockerfile:55-63` (no `-agentlib:jdwp`) |
| §1.4 UFW 22/80/443; fail2ban sshd + `docker-nginx` jail on `limit_req` lines; 4 GB swap; sysctl | **VERIFIED** | `server-setup.sh:56-61, 65-97, 99-131` |
| §1.5 `conf.d` cannot load as committed (two `upstream logistica_backend`); `ssl-setup.sh` deletes `logistica.conf` and rewrites `app-https.conf`; port 80 ACME root `/var/www/certbot` not mounted; TLS 1.2/1.3, headers without CSP, logs to files, `location /mono/` only, no rate limit; `logistica.conf` had limits 10 r/s burst 20 and 5 r/min burst 5 and `/nginx-health` | **VERIFIED** | `nginx/nginx.conf:15`; `app-https.conf:2-5, 8-22, 14-16, 31-41, 44-47, 50-51, 54-74, 77-79`; `logistica.conf:2-5, 8-9, 23, 37, 67-71`; `ssl-setup.sh:85-175`; `docker-compose.yml:127-132` (no certbot mount); `README.md:364-367` |
| §1.5 TLS: certbot `--standalone` after stopping nginx, copied to `./ssl` with `chmod 644` (private key included), cron 03:00 with only a post-hook restart → renewal cannot bind :80 and never re-copies (inferred) | **VERIFIED** (logic) | `ssl-setup.sh:48-63, 73-78, 192-198` |
| §1.5 no `forward-headers-strategy`; nginx resolves `app` at start | **VERIFIED** / inferred | grep `forward` in `src/main` → none; `app-https.conf:2-5` |
| §1.6 profile `prod`; `/app/config/application-prod.yml` mounted from the server; `application.prod.yml` and `application.local.yml` never loaded (dot names); key names of `application.yml` (`ddl-auto` :32, SQL logging :36-38, `mvc.pathmatch` :44-46, `cloud.aws` :48-57, `management` :59-66, `app.*` :68-82); env var names in compose | **VERIFIED** (keys and names; values NOT CHECKED) | `docker-compose.yml:54, 63, 99`; `Dockerfile:62`; `src/main/resources/` listing; `application.yml:1-82`; `application.prod.yml:79-93, 96-110`; `docker-compose.yml:55, 68-70, 73-75, 78-79, 82-84, 87, 90` |
| §1.6 `.env` sets `EMAIL_SENDER_ORIGIN` instead of `EMAIL_ORIGIN`; CORS template lists apex/`www`/`app.` | **NOT CHECKED** (`.env` deleted) | compose side verified: `docker-compose.yml:83` default `logisticasanchez.com`; CORS code `WebSecurityConfig.java:57-68` |
| §1.7 MySQL 8.0.35, `lsdb`, `LSADMIN`, utf8mb4/utf8mb4_unicode_ci, TZ Europe/Rome, `mysql_data`; JDBC `serverTimezone=Europe/Rome`; no Flyway/Liquibase | **VERIFIED** | `docker-compose.yml:6, 11-12, 14, 18, 22-23, 68`; `pom.xml:19-183` |
| §1.8 SES through SDK v1, region hard-coded `EU_NORTH_1`, `SimpleEmailServiceJavaMailSender`; link builders and fixed `ordini.`/`dashboard.` links; logo path | **VERIFIED** | `AwsMailConfig.java:20-31` (`:25`); `LinkUtil.java:14-40`; `PathConstants.java:59-61, 64`; `templates/tracking-state.html:48` (`www…/#tracking`) |
| §1.9 no actuator; HEALTHCHECK commented; scripts probe `/mono/` → generic 500 handler; `fix-actuator.sh` proposes a `HealthController`, an `nc` HEALTHCHECK, a catch-all `fixed.conf`, a Dockerfile rename and `exposure '*'`; README advertises actuator | **VERIFIED** | `pom.xml`; `Dockerfile:71-73`; `deploy.sh:8`; `monitoring.sh:6`; `ErrorController.java:30-33`; `fix-actuator.sh:39-64, 70-107, 113-147, 175-177, 219-224`; `README.md:125-127, 277` |
| §1.10 logback: console + `./logs/spring-boot-logger.log`, daily/10 MB rollover, no `maxHistory`/`totalSizeCap`, DEBUG on `DispatcherServlet` and `RequestMappingHandlerMapping`; body logging at INFO; host logrotate covers only `/opt/logistica-sanchez/logs/*.log` | **VERIFIED** | `logback.xml:4-29, 38-39`; `RequestResponseLoggingFilter.java:37-45`; `server-setup.sh:140-154` |
| §1.11 backups: dump flags, gzip + `gunzip -t`, config tarball **including `.env`**, uploads tarball, 7-day retention, `MAX_REMOTE_BACKUPS` unused, optional rsync `--delete` and S3 Glacier IR with the SES key; `deploy.sh` dumps without sourcing `.env`; restore only in README | **VERIFIED** | `backup.sh:8-9, 13, 42-50, 54, 68-83, 93-99, 105-110, 123-131, 134-148, 151-166`; `deploy.sh:60-81` (no `source`); `README.md:47-51, 161-174` |
| §1.12 monitoring loop 60 s, thresholds, `< 3` containers, TLS 30 days, `mail` + Slack, `nohup` daemon; node-exporter/cAdvisor on localhost in a generated compose file; README claims cAdvisor at `:8090` on the domain | **VERIFIED** | `monitoring.sh:6-12, 44-120, 173-176`; `server-setup.sh:156-194`; `README.md:135` |
| §1.13 README procedure; `.env.example` and `scripts/status.sh` missing; `deploy.sh` pulls `main`, rolling update with `--scale app=2` against fixed names/ports, health abort without `--force`, nginx recreate, prune; `server-setup.sh` pre-creates the directory | **VERIFIED** (merge-conflict claim NOT CHECKED: `main` is not in this clone) | `README.md:11-56, 33, 465`; `deploy.sh:55, 85, 92-102, 108, 112-124, 147, 151, 155`; `docker-compose.yml:47, 93`; `server-setup.sh:135` |
| §1.14 local environment broken as committed (`DB_*` variables, `docker/Dockerfile`) | **VERIFIED**; `local-setup.sh` part NOT CHECKED | `docker-compose.local.yaml:9-12, 29`; `Dockerfile.local` exists at the root, `docker/` NOT FOUND |

### 2.4 12d §2, §3, §4, §6

| Claim | Status | Evidence |
|---|---|---|
| §2 every version in the table (Java 17, Boot 3.2.2, Framework 6.1.3, Security 6.2.1, Tomcat 10.1.18, Hibernate 6.4.1, Thymeleaf 3.1.2, Jackson mix, Logback 1.4.14, Connector/J 8.3.0, HikariCP 5.0.1, SnakeYAML 2.2, Lombok 1.18.30, jjwt 0.11.5, SDK 1.12.341 + 1.11.792, `ion-java` 1.0.2, joda 2.8.1, Spring Cloud AWS 2.2.6, `javax.mail` 1.6.2, PDFBox 2.0.24, flying-saucer 9.1.22, iText 2.1.7, BouncyCastle 1.64 + 138, MySQL 8.0.35, unpinned nginx, floating base images) | **VERIFIED** | `pom.xml:8, 17, 55-77, 96-145`; `dependency-tree` output; `docker-compose.yml:6, 119`; `Dockerfile:2, 23`. Lifecycle dates: see §2.2 and §3 item 1. |
| §3 F-01 (EP-09 and EP-18 unguarded; URL rule on `/mono/cms/**` behind `permitAll`; nginx adds nothing) | **VERIFIED** (code) | `OrderController.java:55-59`, `CmsController.java:79-80` (no `@PreAuthorize`; `:125-126` has it); `WebSecurityConfig.java:41-42`, `:53-54` (ignores any URI without `/mono`); `PatternConstants.java:6`; `app-https.conf:54-74` |
| §3 F-02, F-06 (committed logs, Postman passwords) | **NOT CHECKED** (deleted); tracked-ness **VERIFIED** | `git status`: `D logs/spring-boot-logger.log` + 20 `logs/archived/*.log` (21 files, as 12d says), `D api/sanchez-logistica.postman_collection.json` |
| §3 F-03, F-04, F-07 (committed AWS pair B, JWT key, DB password defaults) | **NOT CHECKED** (values redacted); key positions **VERIFIED** | `application-dev.yml:17-19, 71, 75-76`; `application.local.yml:71, 75-76`; `application-test.yml:71, 75-76`; `application-h2.yml:75, 79-80`; raw string used as HMAC key `JwtService.java:40-42` |
| §3 F-05 `.env`, `.env.local`, `local-setup.sh` tracked; `.gitignore` (33 lines) ignores none of `.env`, `logs/`, `ssl/`, `backup/`, `config/` | **VERIFIED** | `git status` (`D .env`, `D .env.local`, `D local-setup.sh`); `.gitignore:1-33` |
| §3 F-08…F-14, F-17, F-19 | **VERIFIED** | as cited in §2.3 rows (TLS, backups, logs, deploy, rate limits, management keys, dot-named files, 5005, root password on command lines `docker-compose.yml:28`, `backup.sh:44`, `monitoring.sh:70-71`, compose `version:` key `:1`, compose "latest" download `server-setup.sh:45`) |
| §3 F-15, F-16 (CORS `allowCredentials(true)`; plain-HTTP dev/UAT origins) | **VERIFIED** / NOT CHECKED (values) | `WebSecurityConfig.java:63`; `application-dev.yml:69, 79` keys |
| §3 F-18 README is a generic template | **VERIFIED**, with extra evidence | `README.md:20, 28` (`your-repo`), `:33`, `:465`, `:187-188` (`OPTIMIZE TABLE order_table, customer_table, user_table`: tables that do not exist in `lsdb`), `:445-446` (placeholder role mailboxes), `:468` "Last Updated: 2024" |
| §4.1 seven branches, `main` default, lineage | **NOT CHECKED** here | this clone fetches only `migration` (`remote.origin.fetch` = `+refs/heads/migration:refs/remotes/origin/migration`; `git branch -r` → `origin/migration`; `for-each-ref` → 2 refs). The brief's author read GitHub. |
| §4.2 three migration commits, dates and messages; `ca1b0d0` = "http ses fix" (LinkUtil only: `https://`, `ordini.`, `dashboard.`) | **VERIFIED** (messages/dates; the diff NOT CHECKED, `log -p` forbidden) | `git log -3 --date=short`: 2531356 2025-10-03, 285d2b1 2025-10-20, ca1b0d0 2025-11-16; `git log --oneline -15` shows the preceding "v 0.0.2" commits; `LinkUtil.java:15, 20, 25, 29, 33, 38` all `https://` |
| §4.2 "no health endpoint, no schema migrations, no CI in any branch" | **VERIFIED** for `migration` | `pom.xml`; `.github/` NOT FOUND; no `@Scheduled`/`@Profile` beans either |
| §6.6 Q-16 (VPS + SES eu-north-1), Q-21 (EP-09 open), Q-29 link paths, Q-01, Q-02 narrowing | **VERIFIED** | as above |
| §6.7 Q-45 "Caddy" | **NOT FOUND** in `migration` (grep `caddy` → none); `IMPROVEMENTS.md` on `main` NOT CHECKED | — |
| §6.7 Q-46…Q-53 | **NOT CHECKED** (server/AWS/owner facts); the repo-side premises (local-only backups, broken renewal, `EMAIL_ORIGIN` default apex, `ls-web` absent from this clone) hold as cited | `backup.sh:134-166`; `ssl-setup.sh:194-197`; `docker-compose.yml:83` |

## 3. Discrepancies and corrections to the brief (for `docs/plan.md`)

1. **Spring Boot 3.2 end of OSS support** — 12d §0 item 7, §2 and 10 §1 say "November 2024"; as of training knowledge
   the 3.2.x OSS window closed **2024-12-31** (commercial 2026-02-28). Minor; the conclusion ("unsupported") stands.
   Cite as `brief 12d §2` and `brief 10:35`.
2. **"Builds and starts locally: Plausible … Verify"** (10 §1 assessment table) — *builds* is now **VERIFIED** (JDK 21
   with `--release 17`, 38 s, jar produced); *starts* remains **NOT CHECKED** (needs MySQL + SES; forbidden here).
   ADR 0001 should record the split.
3. **Java 17 runtime is still supported** (Temurin 17 to at least Oct 2027 *(TK)*); the criterion "framework *and*
   runtime supported" fails on the framework only. 12d §2 says this; 10 §1 table compresses it into "No". Keep "No"
   but state the reason precisely in ADR 0001.
4. **`JAVA_OPTS` in compose replaces the Dockerfile's flags** (`docker-compose.yml:58-65` overrides `Dockerfile:55-63`):
   production runs with `-Xms512m -Xmx${MAX_HEAP}` and loses `-XX:MaxRAMPercentage`, `-XX:InitialRAMPercentage`,
   `-XX:+UseContainerSupport`, `-Dspring.profiles.active` (compose sets it again at `:63`) and `-Dserver.port=8080`
   (compose sets `SERVER_PORT` at `:55`). 12d §1.3 quotes both without saying one wins; `README.md:191-195` ("container
   memory awareness") is wrong for compose runs. Relevant only to the legacy sizing note of 12d §6.2.
5. **12d §4.1 branch map cannot be re-verified from `legacy/ls`**: the checkout is a single-branch clone. Not a
   contradiction; a verification gap to record (the merge-conflict claim of §1.13 and F-11 depend on `main`).
6. **`scripts/backup.sh:155` reads `<value redacted>`** in this checkout where `ca1b0d0` has an environment-variable
   reference (the local redaction also hit the script; `git status` shows `M scripts/backup.sh`). No secret was there;
   12d §1.11's reading (S3 upload reuses the SES key, `:154`) still holds. Readers must not report it as a finding.
7. **`docker-compose.yml:19-20` mounts `./backup/mysql` and `./init-scripts` into MySQL**; neither directory is in git
   (`server-setup.sh:135` creates them empty) and `backup.sh:42-50` writes through `docker exec` to the host, so both
   mounts are unused. 12d §1.3 lists them as if meaningful. Nothing seeds the schema: Hibernate owns it (12b §1.8 ✓).
8. **The e-mail region in 12d §6.2 ("SES stays in eu-north-1")** rests on `AwsMailConfig.java:25` only; the verified
   identity and the production key are on the server (Q-45). Mark it "(inferred)" in chapter 10 §3.

## 4. Facts the brief does not mention

* The wrapper pins Maven 3.9.5 (`maven-wrapper.properties:1`) while the image uses floating `maven:3.9-…` and the
  pom pins no plugin versions; the artefact is `0.0.2-SNAPSHOT` → image `logistica-sanchez:latest`
  (`docker-compose.yml:46`): no versioned image, no rollback by tag (what 12d §6.2 "Deploy" asks for is new work).
* The fat jar is 81.6 MB / 104 jars: ~8.3 MB are AWS EC2/S3/KMS/CloudFormation SDK 1.11.792 and two BouncyCastle
  copies, none used by the code (grep: no S3/EC2 calls). `jackson-module-jsonSchema` and `validation-api 1.1.0` are
  compile-scope but test-only in practice.
* `application.prod.yml:34-37` configures a `caffeine` cache that is not in the pom; the running cache is
  `ConcurrentMapCacheManager` (`CacheConfig.java:14`). `/app/temp` and `/app/uploads` (`Dockerfile:46`,
  `docker-compose.yml:97-98`, `backup.sh:93-99`) are referenced by no Java code (grep `uploads|/app/temp` → none),
  so the "uploads" backup is always empty.
* `app-https.conf:72-73` sets `Connection "upgrade"` on every proxied request, which defeats the `keepalive 32`
  upstream pool (`:4`) (inferred); `ssl_trusted_certificate` (`:33`) has no effect without `ssl_stapling`.
* `server-setup.sh:148-151` rotates with `create 0640 www-data adm` and sends `USR1` to PID 1 of the app container
  (`sh`, `Dockerfile:76`): no effect on the JVM's own file appender (inferred).
* `monitoring.sh:102` alerts when fewer than 3 containers run; with the monitoring stack (`server-setup.sh:162-194`)
  the normal count is 5, so the threshold never fires for a single failed container.
* No `@Scheduled`, `@Async` or `@Profile` beans exist (grep): the legacy has no background work at all, which matches
  "no outbox" (12d §1.8) and bounds what Path A would reuse.
* Build-environment facts for ADR 0001: JDK 21.0.12.1 (Ubuntu) and Maven 3.9.11 (`/opt/maven`) available; outbound
  Maven Central works through the proxy; Docker CLI 29.8.2 present but **no daemon** (Testcontainers unusable here;
  PROMPT "Environment check": use `TEST_DATABASE_URL`).

## 5. Rules and texts to port (with the chapter 09 IDs they serve)

* **API-01, API-02, API-03, MIG-03** — the only public surface of the legacy host is `location /mono/` on
  `app.logisticasanchez.com` (`app-https.conf:28, 54`); the new proxy/app must answer `/mono/api/order/trace/state`,
  `/mono/api/enumeration/{statoTracking,servizioAccessorio}` and give 410 elsewhere, and keep the e-mail link paths
  `/mono/email-confirm`, `/mono/report`, `/mono/download-report`, `/mono/img/logistica-sanchez-logo.jpg`
  (`LinkUtil.java:14-40`, `PathConstants.java:59-64`) plus `https://www.logisticasanchez.com/#tracking`
  (`tracking-state.html:48`) — Q-29/Q-30/Q-50.
* **NFR-02** — legacy headers to keep: HSTS `max-age=31536000; includeSubDomains`, `nosniff`, `X-Frame-Options
  SAMEORIGIN` (`app-https.conf:44-47`); drop `X-XSS-Protection`, add CSP. The only legacy rate-limit numbers are
  `10 r/s burst 20` (API) and `5 r/min burst 5` (auth) in the deleted `logistica.conf:8-9, 23, 37`: use as a floor for
  the limits of 10 §4. TLS policy `TLSv1.2 TLSv1.3` with ECDHE-GCM suites (`:36-41`) is fine to carry over.
* **NFR-04** — replace the commented `HEALTHCHECK` (`Dockerfile:71-73`) with a real one on the health endpoint; keep
  the non-root user (`Dockerfile:39-40, 66`), `TZ=Europe/Rome` (`Dockerfile:34`, `docker-compose.yml:14`) and the font
  packages for PDF output (`Dockerfile:26-31`: fontconfig + DejaVu) — **ADM-09** needs them with OpenPDF too.
* **NFR-05, MIG-01, MIG-03** — the dump recipe for the cut-over: `mysqldump --single-transaction --routines
  --triggers --events --add-drop-database --databases lsdb` inside `logistica-mysql` (`backup.sh:42-50`); source
  collation `utf8mb4_unicode_ci` (`docker-compose.yml:22-23`, case-insensitive matching of codes and e-mails, 12b);
  JDBC/JVM timezone `Europe/Rome` since October 2025 (`docker-compose.yml:68`, `Dockerfile:34`) for the timestamp
  mapping of 12d §1.7. Never `docker compose down -v` on the legacy VPS (`docker-compose.yml:17-18, 147-149`).
* **NFR-07** — the README's maintenance-mode recipe (`README.md:377-408`) is the only Italian user-facing text in the
  infrastructure ("Manutenzione in corso", "Il sistema sarà disponibile a breve. Ci scusiamo per il disagio."): the
  new proxy's maintenance page can reuse the message (owner reviews at the gate). Runbook steps to port with the
  legacy procedure: restore (`README.md:161-174`), nginx reload (`:228`).
* **EML-01…EML-09** — sender = `EMAIL_AUTHOR` default (`docker-compose.yml:82`), region eu-north-1
  (`AwsMailConfig.java:25`), link origin = `EMAIL_ORIGIN` default apex (`:83`); e-mails are sent synchronously from
  services with no outbox: the new `notifications` module replaces this entirely.
* **Not to port**: CORS with credentials (`WebSecurityConfig.java:57-68`, same-origin now), JWT (`JwtService`), the
  `/mono` ignore rule (`:53-54`), body logging (`RequestResponseLoggingFilter`), `ddl-auto` (`application.yml:32`),
  management `'*'` (`:59-66`, `application.prod.yml:79-93`), `deploy.sh`, `fix-actuator.sh`.

## 6. Risks and unknowns

* **F-01 / Q-21** (live, critical) confirmed again in code; outside this reader's remit but it conditions the freeze.
* **F-02…F-07, Q-53**: NOT CHECKED here; only tracked-ness verified. Any Path A import must come from a fresh export
  the owner makes after the purge, never from this modified worktree and never with history (10 §1 Path A rule).
* **F-08 / Q-49** TLS renewal logic verified broken; **F-09 / Q-48** backups verified local-only by default; both are
  owner actions before Gate 1 (12d §6.7 defaults).
* **F-11**: `deploy.sh` is unsafe for the reasons verified (`:55, 92-102, 108`); the merge-conflict part is NOT CHECKED.
* **Q-45**: no Caddy anywhere in `migration`; the deployed proxy and commit are owner facts (12d §1.15 commands).
* **Q-52**: engine. Path A would keep MySQL (8.0.35 → 8.4 LTS upgrade plus a Flyway baseline of a Hibernate-generated,
  possibly drifted schema, needing the `--no-data` dump of Q-02 first); Path B uses PostgreSQL and the dump importer.
* **Toolchain**: JDK 21 here (no 25); Lombok 1.18.30 would break on 25 under Path A *(TK)*; no Docker daemon → no
  Testcontainers in this environment; CI must provide the same JDK (PROMPT: pin one that exists in both).
* **Supply chain**: AWS SDK v1 is past end of support (2025-12-31) and Boot 3.2 past commercial support; scanners will
  flag BouncyCastle 1.64/138, `ion-java` 1.0.2, Tomcat 10.1.18 and Spring 6.1.3/6.2.1 at once. Under Path A no
  "upgrade in place" is possible without touching every layer (12d §2 implication list verified).
* **Unknown**: whether the application actually starts with the committed defaults (test profile, H2) — not tried.

## 7. Notes for the planner

**ADR 0001 inputs (Path A criteria, my assessment).**

| Criterion (10 §1) | Verdict | Evidence |
|---|---|---|
| Builds and starts locally | Builds: **yes** (38 s, JDK 21, `--release 17`). Starts: **unverified**; plausible with a MySQL and dummy SES values; no health endpoint to prove it. | §2.1 |
| Framework and runtime supported | **No** (framework): Boot 3.2.2 OSS ended 2024-12-31, Spring Cloud AWS 2.2.6 and SDK v1 EOL, Hibernate 6.4 EOL; runtime Java 17 still supported but two LTS behind. | §2.2 |
| Dependencies upgradable with no known critical vulnerability left | **Only by replacement, not by bumping**: Boot 3.2 → 4.0 (Framework 7, Hibernate 7) drags every Spring artefact; SES needs SDK v2 (code rewrite of `AwsMailConfig`/`AwsEmailService`); PDF needs OpenPDF instead of iText 2.1.7 (`HorizontalPdfGenerator` + flying-saucer API change); Jackson/Lombok/javax.mail cleanup; no critical CVE is *reachable* today as far as known (nginx in front, no writable servlet, no `UriComponentsBuilder`), but CVE-2025-41234 touches `WebController.downloadReport`. | §2.2 |
| Schema evolvable with forward-only migrations | **Possible but blind**: `ddl-auto: update`, no migration tool, schema known only from entities (12b) until the owner's `--no-data` dump (Q-02); a Flyway baseline on MySQL is feasible, then the chapter 08 §2 target means renaming every table/column anyway. | `application.yml:32`, `pom.xml` |

**Cost, rough.** What Path A would reuse: ~6.6 k lines of Java in 149 main files (7.3 k in 161 files with tests), of which the business rules (services,
enums, converters, four entities), the seven Thymeleaf e-mail templates behind the ten e-mails (plus three page templates) and the PDF generator — perhaps 1.5–2.5 k lines —
are worth keeping; the 36 controller mappings, ~45 DTOs, JWT/CMS security, logging filter and all scripts are retired
by API-03, 10 §4 and 12d §3. Path A adds, on top of everything Path B also needs (public site, customer area, back
office, outbox, audit, roles, tests, CI, deploy, migration importer): the framework/SDK/PDF replacement (≈ 3–5
agent-days), the secret scan + history-free import + owner rotation loop (≈ 1–2, plus owner time), the MySQL 8.4 +
Flyway baseline on a drifted schema with the same dry-run/reconciliation duties as MIG-01/02 (≈ 3–5), and the
mapping of the `mono.*` packages onto the 10 §2 modules (≈ 2–3). It saves the port of rules and templates
(≈ 2–4 days under B, with chapter 12 already written). Net: **Path A costs roughly 20–35 % more than Path B** and
carries higher regression risk during the upgrade, while the parity risk B was meant to address is already covered
by chapter 12 plus the parity matrix. Recommendation: **Path B on Java + Spring Boot (current), PostgreSQL** (Q-01,
Q-52 defaults), reading `legacy/ls` for rules.

**Stack pins for ADR 0001.** JDK **21** (present here; 25 is not; CI must match); Maven with the wrapper (works through
the proxy); Spring Boot current (4.0.x line if CI and the libraries allow, else latest 3.5.x — decide at Phase 1
start and pin exactly); Flyway; AWS SDK v2 SESv2 in eu-north-1 with a dedicated IAM user; OpenPDF; Testcontainers
in CI with the `TEST_DATABASE_URL` fallback here.

**Module ownership of what this reader found.** `platform`: Dockerfile (non-root, TZ, fonts, healthcheck),
`deploy/` (compose, Caddy or nginx with working renewal, json-file rotation, `.env.example`, bootstrap script,
runbooks of NFR-07), `.gitignore` guards + secret scanning (NFR-02), health endpoint and logs without bodies (NFR-04),
backup/restore jobs (NFR-05). `tracking`/`site`: the `/mono/*` compatibility and 410 map (API-01…03) and the
`#tracking` anchor. `notifications`: SES config, link paths of the old e-mails (EML-*). `migration`: dump recipe,
collation and timezone notes (MIG-01…03). `reports`: fonts + OpenPDF (ADM-09).

**Phase 1 needs.** A `deploy/` folder from day one with a versioned image tag (not `latest`); a healthcheck that
exists before any script probes it; `.gitignore` covering `.env*`, `ssl/`, `backup/`, `config/`, `logs/`; CI with
JDK 21 and a PostgreSQL service; a local profile that boots with fakes (mail catcher, SES/S3 fakes) and refuses to
start without secrets in prod; a `maintenance` page in the proxy; the cut-over runbook must include "never run
`scripts/deploy.sh` or `docker compose down -v` on the legacy VPS". Gate 0 items for the owner: Q-45, Q-48, Q-49,
Q-52, Q-53, plus the Boot line to pin.
