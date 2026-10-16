# 12d · Legacy backend: infrastructure, configuration, deployment, repository hygiene, branch history

> **Status.** Written from the legacy backend source (`ls`, branch `migration`, commit
> `ca1b0d0`) on 2026-10-16. Its corrections to chapters 01–11 have been applied there.
> Where this chapter and an older chapter still disagree about how the legacy system
> works, **this chapter wins**: it reads the server code, while 02–08 inferred the server
> from its clients. Sections titled "Reconciliation" or "Corrections to the brief" record
> what changed.

Scope: the hosting, configuration, deployment, operations, repository hygiene and branch
history of the legacy backend, plus what `ls-web` is, and the corrections these imply for
the brief (chapters 01, 08, 10, 11).

Sources (all read-only):
* Backend repo `FrancescoCorbosiero/ls`. It is **private** on GitHub with 0 forks, and its
  default branch is `main` (GitHub REST API). I read all 7 branches with `git show` and
  `git log`. Nothing was checked out, edited or pushed. The extracted copy of `migration`
  that was read is byte-identical to `origin/migration` = `ca1b0d0` (checked with `diff -r`
  against `git archive`).
* `ls-web`, cloned read-only at `abe31e4`.
* The brief in `webapp-brief/docs/brief/`.

Method notes:
* Production was not contacted, and no DNS lookups were made.
* Secret values are never reproduced. Findings name the file and line only. Whether two
  values are equal was checked locally, with no output.
* Facts about the logs are aggregate counts from pattern matching.
* Resolved dependency versions come from `mvn dependency:tree` run on a copy of the pom
  alone.

Citation format: `ls@<branch|sha>:<path>:<line>`, `ls-web@abe31e4:<path>:<line>`, and
`brief NN:<line>` for the brief's chapter files. **(inferred)** marks a conclusion drawn
from code or configuration that the sources do not state.

---

## 0. Key facts on one screen

1. **Production is most likely the branch `migration` at `ca1b0d0` (2025-11-16)** (inferred,
   high confidence; evidence in §4.3).
   * It runs on a **Contabo VPS (Ubuntu) with Docker Compose**: `nginx` (Let's Encrypt TLS)
     → `app` (Spring Boot 3.2.2 on Java 17, port 8080, context path `/mono`) → `mysql`
     (MySQL 8.0.35, database `lsdb`). The only host it serves is `app.logisticasanchez.com`
     (ls@migration:scripts/server-setup.sh:10, docker-compose.yml:6,
     nginx/conf.d/app-https.conf:28).
   * It moved off AWS Elastic Beanstalk and RDS in October 2025
     (ls@2531356, ls@285d2b1 "Stable - Migration complete").
   * AWS is still used, but only for **SES in eu-north-1, with a static IAM access key**
     (ls@migration:src/main/java/it/logisticasanchez/mono/configuration/AwsMailConfig.java:20-26).
2. **Part of the production configuration lives outside git**: the server's `.env` and
   `config/application-prod.yml`, which is mounted into the container
   (ls@migration:docker-compose.yml:99). Spring never loads the committed
   `application.prod.yml`, because its name has a dot instead of a hyphen (inferred).
3. **There is no working health check.**
   * No actuator dependency is present, so no `/actuator/*` endpoint exists.
   * The Docker HEALTHCHECK is commented out.
   * The scripts probe `GET /mono/`, which answers HTTP 500 (inferred).
4. **The operations scripts are partly broken.**
   * `deploy.sh` pulls `main`, which would now cause a merge conflict on the server
     (verified in a scratch clone).
   * Its "rolling" deploy cannot work with fixed container names.
   * Backups are local-only by default, with 7-day retention.
   * TLS auto-renewal is probably broken (inferred).
5. **Critical, live (Q-21 is answered "yes")**: `POST /mono/api/order/pending` (EP-09) has no
   authorization check, so the list of pending orders, with customer data, is public.
   `POST /mono/cms/order/report` (EP-18) is also unguarded. Both are inferred from code;
   probe P-04 should confirm them.
6. **Repository hygiene problems:**
   * 11 MB of developer logs are committed on `migration`. They contain personal data,
     plaintext passwords and JWTs.
   * A format-valid AWS key pair is committed as a default in 4 config files. It is not the
     pair the owner declared synthetic on 2026-07-29.
   * A JWT signing key is committed as a default.
   * The Postman collection contains passwords.
   * `.gitignore` does not exclude `.env`, `logs/`, `ssl/` or `backup/`.
7. **The stack is end-of-life**: Spring Boot 3.2 (OSS support ended November 2024), AWS SDK
   for Java v1 (end of support December 2025), Spring Cloud AWS 2.2.6 (built for Boot 2),
   iText 2.1.7 (from 2009) and MySQL 8.0 (EOL April 2026).
8. **`ls-web` has nothing to do with the backend.** It is a single prompt file, `PROMPT.md`,
   for building a generic static event or marketing site with Astro. It contains nothing
   sensitive.
9. **The brief must change in these places:**
   * Chapter 01: the backend source is now available; it runs on a VPS, not on Elastic
     Beanstalk; the CMS host is `dashboard.`.
   * Chapter 10 §3: assume a VPS with Docker Compose, plus SES.
   * Chapter 10 §7 and 08 §4.7–4.9: block legacy writes at the VPS nginx, dump the local
     MySQL, add `dashboard.` to the DNS plan, and retire the VPS and the AWS leftovers
     safely.

---

## 1. Current production architecture (as the repo describes it)

### 1.1 Topology

```
Internet ─► Contabo VPS, Ubuntu 20.04+ (README.md:6), UFW allows 22/80/443, app dir /opt/logistica-sanchez
            compose project "logisticasanchez" (ssl-setup.sh:10,50) (inferred: the live stack)
 :80/:443 ─► logistica-nginx (nginx:alpine)      server_name app.logisticasanchez.com
               :80  → 301 https (the ACME webroot location is not mounted: unusable)
               :443 TLS, Let's Encrypt cert copied into ./ssl;  location /mono/ → app:8080
                  │ docker network "frontend"
             logistica-app (image logistica-sanchez:latest; Spring Boot 3.2.2 / Java 17 Temurin JRE alpine; uid 1001)
               :8080 (host 127.0.0.1:8080), context /mono, profile prod, TZ Europe/Rome
               config = classpath application.yml + /app/config/application-prod.yml (server-only) + env from .env
               volumes app_logs (/app/logs), app_temp, app_uploads
                  │ docker network "backend"     jdbc:mysql://mysql:3306/lsdb?useSSL=false…
             logistica-mysql (mysql:8.0.35)  DB lsdb, user LSADMIN, volume mysql_data, host 127.0.0.1:3306
            host side: cron backup.sh 02:00 (README.md:50), cron certbot renew 03:00 (ssl-setup.sh:196),
                       monitoring.sh nohup loop, optional node-exporter :9100 / cAdvisor :8090 (localhost)
 outbound ─► AWS SES eu-north-1 (SDK v1, static key) · optional rsync / S3 Glacier backups
Elsewhere, not served by this stack: public site (apex/www), ordini. (portal), dashboard. (CMS)
AWS leftovers, status unknown: EB UAT environment, RDS MySQL instances, S3 website buckets
```

### 1.2 Hosts and domains

| Host | Role | Evidence | Notes |
|---|---|---|---|
| `app.logisticasanchez.com` | Backend: the nginx `server_name` and the TLS certificate. Paths are under `/mono/` | ls@migration:nginx/conf.d/app-https.conf:11,28; scripts/ssl-setup.sh:5,8; scripts/monitoring.sh:108 | Must resolve to the VPS, because the certificate is obtained with certbot `--standalone` HTTP-01 (ssl-setup.sh:56-63) (inferred). |
| `logisticasanchez.com` (apex) and `www.` | Public site. Listed in the CORS template. The apex is also the default host for e-mail links | ls@migration:.env:25; docker-compose.yml:83 (`EMAIL_ORIGIN` default); templates/tracking-state.html:48 (`https://www.logisticasanchez.com/#tracking`) | Not served by the committed nginx (app-https.conf:28) (inferred). |
| `ordini.logisticasanchez.com` | Order portal. Linked from backend e-mails since 2025-11-16 | ls@migration:src/main/java/it/logisticasanchez/mono/business/util/LinkUtil.java:24-26; commit ls@ca1b0d0 | Hosting is not in the repo. |
| `dashboard.logisticasanchez.com` | **CMS.** Linked from backend e-mails since 2025-11-16 | ls@migration:…/business/util/LinkUtil.java:28-30 | **The brief does not mention this host.** Hosting is not in the repo. |
| `uat-sanchez-logistica.eu-north-1.elasticbeanstalk.com` | AWS-era UAT backend, plain HTTP | ls@migration:src/main/resources/application-dev.yml:79; one logged report e-mail links to it | Unknown whether it still runs. |
| `sanchez-logistica-db-1.cdmko0cyu1mk.eu-north-1.rds.amazonaws.com` | AWS-era RDS MySQL. The default URL of the `dev` profile | ls@migration:src/main/resources/application-dev.yml:17 | Unknown whether it still exists. |
| `db-logistica-sanchez-1.cdmko0cyu1mk.eu-north-1.rds.amazonaws.com` | An earlier RDS instance (February 2024) | ls@1c65922:src/main/resources/application.yml:8 | Unknown whether it still exists. |
| `sanchez-logistica-fe` and `sanchez-logistica-cms` (`.s3-website.eu-north-1.amazonaws.com`) | AWS-era portal and CMS hosting. E-mail links pointed there until 2025-11-16 | ls@migration:src/main/resources/application-dev.yml:69; diff of ls@ca1b0d0 | See brief LPS-S4: keep the bucket names for good. |
| `api.logisticasanchez.com` | Not referenced anywhere in the backend repo | — | Appears only commented out in the frontends (brief 02 §2). |
| `servizioclienti@logisticasanchez.com` | SES sender, monitoring alerts, Let's Encrypt contact | ls@migration:docker-compose.yml:82; scripts/monitoring.sh:7; scripts/ssl-setup.sh:9 | Must be a verified SES identity in eu-north-1 (inferred). |

### 1.3 Containers (`ls@migration:docker-compose.yml`)

| Service / container | Image | Published ports | Volumes and mounts | Other settings |
|---|---|---|---|---|
| `mysql` / `logistica-mysql` (lines 5-39) | `mysql:8.0.35` (6) | `127.0.0.1:3306` (16) | `mysql_data:/var/lib/mysql`, `./backup/mysql:/backup`, `./init-scripts:/docker-entrypoint-initdb.d` (17-20) | `utf8mb4`/`utf8mb4_unicode_ci`, `max_connections=200`, `innodb_buffer_pool_size=256M`, `innodb_log_file_size=64M` (21-26); `TZ=Europe/Rome` (14); healthcheck `mysqladmin ping` every 30 s, 5 retries (27-32); `restart: unless-stopped`; json-file logs 10 MB × 3 |
| `app` / `logistica-app` (42-115) | `logistica-sanchez:latest`, built from `Dockerfile` (43-47) | `127.0.0.1:8080`, `127.0.0.1:5005` (92-94). Port 5005 is meant for debugging, but no JDWP agent is configured in `JAVA_OPTS`, so nothing listens there (inferred) | `app_logs`, `app_temp`, `app_uploads`, and `./config/application-prod.yml:/app/config/application-prod.yml:ro` (95-99) | `depends_on` mysql healthy; `SPRING_PROFILES_ACTIVE=prod` (54); `JAVA_OPTS -Xms512m -Xmx${MAX_HEAP:-1024m}` with G1 (58-65); limits 2 CPU / 1536 MB, reservation 0.5 CPU / 512 MB (108-115); json-file logs 20 MB × 5; **no healthcheck** |
| `nginx` / `logistica-nginx` (118-139) | `nginx:alpine`, unpinned | `0.0.0.0:80`, `0.0.0.0:443` (124-126) | `nginx.conf` and `conf.d` (read-only), `./ssl:/etc/nginx/ssl:ro`, `nginx_cache`, `nginx_logs` (127-132) | `depends_on: app` (order only) |
| `node-exporter`, `cadvisor` | `prom/node-exporter:latest`, `gcr.io/cadvisor/cadvisor:latest` | `127.0.0.1:9100`, `127.0.0.1:8090` | host `/proc`, `/sys`, `/`, docker dir (read-only); cAdvisor privileged | Defined in `docker-compose.monitoring.yml`. That file is **generated on the server** by ls@migration:scripts/server-setup.sh:162-194 and is not in git. |

Image build (`ls@migration:Dockerfile`):
* Multi-stage build on `maven:3.9-eclipse-temurin-17-alpine`, running `mvn clean package
  -DskipTests` (2, 11, 17-20). **Tests never run during the build.**
* Runtime image `eclipse-temurin:17-jre-alpine`, with curl, tzdata and fonts for PDF output
  (23-31). `TZ=Europe/Rome` (34-36). Runs as non-root user `spring` (39-40, 66).
* The exploded jar puts the classes in `/app` (50-52). The entrypoint is
  `sh -c "java $JAVA_OPTS -cp .:lib/* it.logisticasanchez.mono.MonoApplication"` (76).
* The HEALTHCHECK has been commented out since ls@285d2b1 (71-73).

Compose project names do not match.
* `ssl-setup.sh` and `fix-actuator.sh` use `docker compose -p logisticasanchez`
  (ls@migration:scripts/ssl-setup.sh:10,50; scripts/fix-actuator.sh:10).
* `deploy.sh` and the README use plain `docker-compose`, so the project name is taken from
  the directory, `logistica-sanchez`.
* Every container has a fixed `container_name`, so the two project names cannot coexist
  (inferred). The live stack was probably started with `-p logisticasanchez` (inferred).

### 1.4 Network, ports, firewall

* **Public**: 80 and 443, both on nginx. SSH on 22. UFW denies everything else by default
  and allows 22, 80 and 443 (ls@migration:scripts/server-setup.sh:56-61).
* **Localhost only**: 3306 (MySQL), 8080 (app), 5005 (debug, unused), 9100
  (node-exporter), 8090 (cAdvisor).
* Docker networks: `backend` (mysql, app) and `frontend` (app, nginx)
  (ls@migration:docker-compose.yml:141-145).
* Docker-published ports bypass UFW. That is harmless here, because only nginx publishes on
  0.0.0.0 (inferred).
* The app listens on 8080 (`SERVER_PORT`; docker-compose.yml:55, Dockerfile:63). The base
  config defaults to 9050 when `SERVER_PORT` is unset (application.yml:2).
* Context path: `/mono` (application.yml:4).
* Public base URL: `https://app.logisticasanchez.com/mono/…`. A request for `/` gets a 301
  to `/mono/` (app-https.conf:77-79).

### 1.5 Reverse proxy and TLS

* `nginx.conf` includes `conf.d/*.conf` (ls@migration:nginx/nginx.conf:15).
* **The committed `conf.d` cannot be loaded as it is.** Both `app-https.conf:2-5` and
  `logistica.conf:2-5` define `upstream logistica_backend`, and a duplicate upstream is a
  fatal nginx error (inferred). `ssl-setup.sh` deletes `logistica.conf` and rewrites
  `app-https.conf` (ssl-setup.sh:85-175), so the server presumably runs `app-https.conf`
  alone (inferred).
* What `app-https.conf` does:
  * **Port 80**: an ACME location with root `/var/www/certbot`. That directory is not
    mounted into the container, so the location is unusable (inferred). Everything else gets
    a 301 to https (8-22).
  * **Port 443** (`ssl http2`):
    * TLS: certificates from `/etc/nginx/ssl/{fullchain,privkey,chain}.pem` (31-33);
      TLSv1.2 and 1.3 with ECDHE-GCM cipher suites; session tickets off (36-41).
    * Headers (44-47): `X-Frame-Options SAMEORIGIN`, `nosniff`, `X-XSS-Protection`, and HSTS
      for 1 year with `includeSubDomains`. **There is no CSP.**
    * Logs go to the files `https_access.log` and `https_error.log` (50-51). These are
      regular files in the `nginx_logs` volume and nothing rotates them (inferred).
    * `location /mono/` proxies to `app:8080` with `Host`, `X-Real-IP` and `X-Forwarded-*`
      headers, 60 s timeouts, and `Upgrade` / `Connection upgrade` (54-74).
    * There is **no rate limiting, no IP restriction and no path blocking.**
* `logistica.conf` is the pre-TLS version, and the server deletes it.
  * It held the rate limits: API 10 requests/s with burst 20, auth 5 requests/min with
    burst 5 (8-9, 23, 37). It also had a `/nginx-health` location (67-71).
  * The README says these limits are active (README.md:364-367). In production they are not
    (inferred).
* **Certificates**: Let's Encrypt for `app.` only.
  * Issued with `certbot certonly --standalone` after stopping nginx (ssl-setup.sh:50-63),
    then copied into `./ssl` with `chmod 644`, which **includes the private key**
    (ssl-setup.sh:75-78).
  * Renewal is a cron job every day at 03:00 with only a post-hook that restarts nginx
    (ssl-setup.sh:194-197).
  * Two problems follow (inferred). The standalone renewal cannot bind port 80 while nginx
    holds it. And even a successful renewal is never copied into `./ssl`. **The certificate
    nginx serves therefore expires 90 days after the last manual run.**
  * `monitoring.sh` would warn 30 days before expiry, but only if it runs and mail works.
* **Forwarded headers**: no profile sets `server.forward-headers-strategy`, so the app sees
  nginx's IP address and the `http` scheme (inferred). That does no harm today, because
  e-mail links are built from configuration, not from the request.
* nginx resolves the `app` upstream only at start. After the app container is recreated,
  nginx must be restarted (inferred). `deploy.sh` does this (deploy.sh:147).
* **Conflicting statement.** The 2026-07-29 analysis on `main` calls the current
  infrastructure "Caddy reverse proxy + Docker + VPS" (ls@main:IMPROVEMENTS.md:5). No Caddy
  configuration exists in any branch. Either the proxy changed after November 2025 outside
  git, or that statement is imprecise. This is open question **Q-45** (§6.7).

### 1.6 Runtime configuration: what actually applies in production

* **Active profile**: `prod` (ls@migration:docker-compose.yml:54,63; Dockerfile:62).
* **Files loaded in production**:
  * the classpath `application.yml`;
  * `/app/config/application-prod.yml`. Spring Boot reads `file:./config/` relative to
    `WORKDIR /app`. The file is mounted from the server's `./config/`
    (docker-compose.yml:99), so **its contents are unknown**;
  * environment variables from the server `.env`.
* **Committed files Spring never reads:**
  * `src/main/resources/application.prod.yml`. Profile files must be named
    `application-{profile}`, and nothing imports this one (repo-wide grep) (inferred).
  * `application.local.yml`, for the same reason. It was renamed from `application-local.yml`
    in ls@285d2b1.
* **Values that apply unless the server's `application-prod.yml` overrides them**
  (ls@migration:src/main/resources/application.yml):
  * `ddl-auto: update` (32): Hibernate changes the production schema at startup.
  * `show_sql`, `format_sql` and `use_sql_comments` set to true (36-38): SQL goes to stdout.
  * `MySQL8Dialect` (35); Hikari max 10 connections, min idle 5 (21-29).
  * Tomcat access log on, in the relative directory `logs` (6-9). That resolves against
    Tomcat's temporary base directory, so the log is not kept (inferred).
* **Keys with no effect:**
  * the top-level `mvc.pathmatch.*` (44-46);
  * `cloud.aws.*` (48-57): Spring Cloud AWS 2.x auto-configuration is not loaded on Boot 3
    (inferred);
  * `spring.main.jvm.memory.maxHeapSize` (13-15), which is not a Spring property;
  * `management.*` (59-66), because actuator is absent;
  * `hibernate.type: trace` (39).
* **Keys the code consumes**:
  * `app.allowed-origins` (69), used for CORS;
  * `app.jwt.jwt-key`, `jwt-exp-token`, `jwt-exp-refresh-token` (70-73);
  * `app.aws.accessKey` and `secretKey` (74-76), used for SES;
  * `app.email.author`, `origin`, `enableAutomaticEmails` (77-80);
  * `app.error.hideDetail` (81-82).

Environment variables (names only; values never reproduced):

| Variable | Used by | Committed default or template value | Remark |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` / `SPRING_PROFILE` | docker-compose.yml:54, Dockerfile:62, .env:31 | `prod` | |
| `SERVER_PORT` | application.yml:2 | 8080 (docker-compose.yml:55) | |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | application.yml:17-19 | compose builds `jdbc:mysql://mysql:3306/lsdb?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Europe/Rome&characterEncoding=UTF-8` (docker-compose.yml:68-70) | No TLS inside the Docker network. |
| `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` | mysql container, scripts | `lsdb`, `LSADMIN`, and placeholder passwords (.env:2-5) | |
| `MAX_HEAP` | docker-compose.yml:60 | `1024m` (.env:8) | |
| `JWT_KEY`, `JWT_TOKEN_EXP`, `JWT_REFRESH_TOKEN_EXP` | application.yml:71-73 | placeholder key (.env:11); 21600 s and 7200 s (.env:12-13) | The refresh token lives shorter (2 h) than the access token (6 h). |
| `AWS_ACCESS_KEY`, `AWS_SECRET_KEY` | application.yml:75-76 → AwsMailConfig.java:23-24 | placeholders (.env:16-17) | Static IAM key for SES. |
| `EMAIL_AUTHOR` | application.yml:78 | `servizioclienti@logisticasanchez.com` (docker-compose.yml:82; .env:20) | |
| `EMAIL_ORIGIN` | application.yml:79 → LinkUtil.java:14-40 | compose default `logisticasanchez.com` (docker-compose.yml:83) | **Name mismatch.** `.env` sets `EMAIL_SENDER_ORIGIN` instead (.env:21). Unless the server `.env` differs, e-mail links therefore go to the apex, `https://logisticasanchez.com/mono/…`, which this stack does not serve (inferred). |
| `ENABLE_AUTOMATIC_EMAILS` | application.yml:80 | `true` | |
| `ALLOWED_ORIGINS` | application.yml:69 → WebSecurityConfig.java:64 | apex, `www.`, `app.` (.env:25) | Does not list `ordini.` or `dashboard.`. |
| `HIDE_ERROR_MESSAGES` | application.yml:82 | `true` (docker-compose.yml:90) | |
| `SLACK_WEBHOOK_URL`, `REMOTE_BACKUP_HOST/PORT/USER`, `AWS_BACKUP_BUCKET`, `BACKUP_NOTIFICATION_EMAIL` | scripts | absent from `.env` | Probably unset in production (inferred). |

CORS (ls@migration:src/main/java/it/logisticasanchez/mono/configuration/security/WebSecurityConfig.java:58-67):
* Exact origins taken from `ALLOWED_ORIGINS`, `allowCredentials(true)`.
* Methods GET, POST, PUT, DELETE and OPTIONS; all headers; max-age 3600 s.
* If the portal and the CMS call `app.` cross-origin, the production value must list
  `ordini.` and `dashboard.`. So the committed `.env` is not the production one (inferred).

### 1.7 Database

* **Engine and storage:**
  * MySQL **8.0.35** in a container (ls@migration:docker-compose.yml:6).
  * Database `lsdb`, application user `LSADMIN` (11-12).
  * Character set `utf8mb4` / `utf8mb4_unicode_ci` (22-23), container timezone
    `Europe/Rome` (14).
  * Data lives in the named volume `mysql_data` (18, 148).
  * The slow-query log was removed in ls@285d2b1.
* **Schema**: Hibernate owns it. The base config uses `ddl-auto=update`
  (application.yml:32), and there is no Flyway or Liquibase in the pom. The production
  schema is therefore whatever Hibernate generated from the entities: first on RDS, then
  imported into the container (inferred).
* **History**: the README documents moving from RDS with `mysqldump` → `scp` → import
  (README.md:241-278). Commit ls@285d2b1 "Stable - Migration complete" (2025-10-20)
  suggests this was done (inferred).
* **Timezone break for the data migration** (inferred; the data lens should confirm):
  * On the VPS the JVM runs in `TZ=Europe/Rome` and JDBC uses
    `serverTimezone=Europe/Rome` (Dockerfile:34; docker-compose.yml:68).
  * On AWS Elastic Beanstalk the defaults were UTC (inferred).
  * The `Instant` timestamp columns (for example
    ls@migration:src/main/java/it/logisticasanchez/mono/persistence/model/SpedizioneEntity.java:68-80)
    may therefore have been written with different session timezones before and after
    October 2025.
  * Check this in the dump before mapping the columns to `timestamptz`.

### 1.8 E-mail

* AWS SES through SDK v1 (`AmazonSimpleEmailService`) with static credentials. The region is
  hard-coded to `EU_NORTH_1`.
* Spring Cloud AWS's `SimpleEmailServiceJavaMailSender` wraps that client
  (ls@migration:…/configuration/AwsMailConfig.java:20-31).
* Links in e-mails (ls@migration:…/business/util/LinkUtil.java:14-40;
  …/configuration/constant/PathConstants.java:59-61,64):
  * `https://{EMAIL_ORIGIN}/mono/email-confirm?email=…&verificationCode=…`;
  * `/mono/download-report?email=…&from=…&to=…&onlyDelivered=…` and `/mono/report?…`;
  * `/mono/img/logistica-sanchez-logo.jpg`, the logo in every e-mail;
  * fixed links to `https://ordini.logisticasanchez.com/` and
    `https://dashboard.logisticasanchez.com/`.
* Before ls@ca1b0d0 (2025-11-16), all these links used `http://`, and the portal and CMS
  links pointed to the S3 website buckets.
* The `dev` profile sets the sender default to the owner's personal Gmail address and the
  link origin to the Elastic Beanstalk UAT host (application-dev.yml:78-79).
* There is no outbox and no bounce or complaint handling (inferred: no SNS or SQS code).

### 1.9 Health checks and actuator

* **No `spring-boot-starter-actuator`** in the pom (ls@migration:pom.xml:19-183) or in the
  resolved dependency tree, so no management endpoint exists in production.
* The management configuration is present but has no effect:
  * application.yml:59-66 exposes `'*'` and enables httptrace;
  * application.prod.yml:79-93 lists health, info, metrics, env and configprops with
    `show-details: always`.
* There is no app healthcheck at all:
  * The Docker HEALTHCHECK on `/mono/actuator/health` is commented out (Dockerfile:71-73,
    since ls@285d2b1).
  * Compose defines no healthcheck for the app.
* The scripts probe a URL that always fails:
  * `deploy.sh` and `monitoring.sh` probe `http://localhost:8080/mono/` (deploy.sh:8,
    monitoring.sh:6).
  * Nothing maps `/` and there is no static index. Spring 6.1 therefore raises
    `NoResourceFoundException`, and the generic handler turns it into **HTTP 500**
    (ls@migration:…/api/error/ErrorController.java:30-33; `GENERIC_500`) (inferred).
  * The logs show this handler answering for missing resources ("No static resource …").
* `scripts/fix-actuator.sh` does not fix anything:
  * It proposes a `HealthController` (never added to `src`) and a HEALTHCHECK based on
    `nc` (fix-actuator.sh:39-64, 70-107).
  * It writes a catch-all nginx `fixed.conf` (113-147), and can rename `Dockerfile`
    (175-178).
  * It ends by recommending `exposure.include: '*'` with `show-details: always`
    (215-224). If actuator were added, that would publish `env` and `configprops` through
    nginx's `/mono/` proxy. **Do not apply it.**
* The README advertises `/mono/actuator/health` and `/metrics` (README.md:125-127), and
  `local-setup.sh` waits for `/mono/actuator/health` (local-setup.sh:123). Both are wrong.

### 1.10 Logging

* **`logback.xml` takes precedence over Boot's `logging.file.*` settings.**
  * It writes to the console and to `./logs/spring-boot-logger.log`, which is `/app/logs`
    inside the container, in the `app_logs` volume.
  * Files roll over daily and at 10 MB into `logs/archived/`. There is no `maxHistory` and
    no `totalSizeCap`, so the logs grow without limit
    (ls@migration:src/main/resources/logback.xml:4-29) (inferred).
  * `DispatcherServlet` and `RequestMappingHandlerMapping` log at DEBUG (38-39).
* **`RequestResponseLoggingFilter` logs every request and response body at INFO**
  (ls@migration:…/configuration/RequestResponseLoggingFilter.java:37-45). In production,
  every login password, every JWT and every customer and order payload therefore lands in
  the log volume and in `docker logs`. Nothing turns the filter off (inferred).
* `logging.*` in `application.prod.yml` (96-110) has no effect twice over: the file is not
  loaded, and `logback.xml` overrides the file settings.
* Docker json-file caps: app 5 × 20 MB; mysql and nginx 3 × 10 MB
  (docker-compose.yml:35-39, 103-107, 135-139).
* nginx access logs use the `combined` format. They hold client IPs and full request URIs,
  including the customer e-mail addresses in report links (inferred).
* The host logrotate rule covers `/opt/logistica-sanchez/logs/*.log`
  (server-setup.sh:140-154). The app logs live in a Docker volume, so only `monitoring.log`
  is rotated (inferred).

### 1.11 Backups (`ls@migration:scripts/backup.sh`)

* **Schedule**: meant to run daily at 02:00 from the root crontab (README.md:47-51). Whether
  the cron entry is installed is unknown.
* **Database**:
  * `mysqldump --single-transaction --routines --triggers --events --add-drop-database
    --databases lsdb`, gzipped to `/opt/logistica-sanchez/backup/mysql/lsdb_<ts>.sql.gz`
    (40-56).
  * Checked with `gunzip -t` (105-110).
* **Configuration**: a tarball of `.env`, the compose files, `Dockerfile`, `nginx/`,
  `config/` and `scripts/` (68-83). **This copies the production secrets, unencrypted.**
* **Uploads**: a tarball of the container's `/app/uploads` (93-99).
* **Retention**: everything older than 7 days is deleted (123-131). `MAX_REMOTE_BACKUPS=30`
  is defined but never used (9).
* **Off-site copies are optional and probably off:**
  * `rsync --delete` to `REMOTE_BACKUP_HOST` mirrors the local folder, so the remote
    retention equals the local one (134-148).
  * An S3 Glacier IR upload uses the same AWS key as SES (151-166).
  * Neither variable is in the committed `.env`, so backups are **probably local-only**
    (inferred). Losing the VPS then means losing the data.
* `deploy.sh` also takes an uncompressed dump before each deploy (deploy.sh:60-81). It does
  not source `.env`, so the dump fails unless `MYSQL_ROOT_PASSWORD` is already exported
  (inferred).
* A restore procedure exists only in the README (README.md:161-174). Restores have never
  been tested (inferred).

### 1.12 Monitoring and intrusion protection

* **`monitoring.sh`** (ls@migration:scripts/monitoring.sh:1-180) is a bash loop that runs
  every 60 s.
  * It is started as a `nohup` daemon with no systemd unit, so it does not restart after a
    reboot (inferred).
  * Checks:
    * app HTTP 200 and latency of 5 s at most;
    * MySQL ping and connection usage above 80 %;
    * CPU above 80 %, memory above 85 %, disk above 90 %;
    * fewer than 3 running containers;
    * TLS certificate of `app.` expiring within 30 days.
  * Alerts go by `mail` to `servizioclienti@` and optionally to a Slack webhook.
    `server-setup.sh` installs no mail transfer agent, so the mail alerts are probably
    silent (inferred).
  * Because of the HTTP 500 on `/mono/` (§1.9), it would raise a CRITICAL alert every
    minute if it ran (inferred).
* **node-exporter and cAdvisor** listen on localhost only, and nothing in the repo scrapes
  them: no Prometheus, no Grafana (server-setup.sh:156-194) (inferred: unused). The README
  claims cAdvisor is reachable at `http://your-domain:8090` (README.md:135). It is not,
  because it is bound to localhost.
* **fail2ban** has an sshd jail and a `docker-nginx` jail that matches `limit_req` log lines
  (server-setup.sh:65-97). The nginx jail is ineffective (inferred), because:
  * the production nginx has no `limit_req`;
  * fail2ban's default iptables actions do not filter traffic that Docker forwards.
* **Host hardening**: UFW, 4 GB swap, sysctl settings (server-setup.sh:99-131). Changing the
  SSH port and disabling root login are only a printed reminder (202).
* There is no external uptime monitor and no error tracking.

### 1.13 Deployment procedure: documented versus workable

The README documents this procedure (README.md:11-56, 79-85):
1. Run `server-setup.sh`.
2. `git clone` into `/opt/logistica-sanchez`.
3. `cp .env.example .env`. That file does not exist; the repo ships `.env` itself.
4. `docker-compose up -d`.
5. `ssl-setup.sh`.
6. Add the backup cron.
7. Start `monitoring.sh -d`.

Later updates go through `scripts/deploy.sh`.

`deploy.sh` runs as root and does this:
1. `git pull origin main` (55).
2. Takes a dump and a tarball (60-81).
3. `docker-compose build app` (85).
4. Checks health and aborts unless `--force` is given (92-102).
5. `--scale app=2` (108).
6. Waits for health (112-124).
7. Recreates nginx (147).
8. Scales back to 1 (151), then prunes images (155).

What actually works (inferred):
* **Running `deploy.sh` today breaks the server checkout.**
  * `main` now contains `IMPROVEMENTS.md` and the deletion of
    `src/main/resources/application.yml`.
  * Merging it into the `migration` checkout causes a modify/delete conflict on that file.
    I verified this with `git merge-tree` in a scratch clone.
  * The script then exits with the repository in the middle of a merge.
* The rolling update is impossible. `--scale app=2` conflicts with
  `container_name: logistica-app` and with the fixed host port `127.0.0.1:8080`
  (docker-compose.yml:47,93). The health URL also returns 500, so without `--force` the
  script stops at step 4.
* `server-setup.sh` pre-creates directories in `/opt/logistica-sanchez` (server-setup.sh:135,
  162). That makes `git clone … .` into it fail, because the directory is not empty.
* The project names differ between scripts (§1.3).
* **The real procedure was therefore manual**: on the VPS, `git pull` the `migration`
  branch, then `docker compose -p logisticasanchez up -d --build`. That means about a minute
  of downtime while Spring Boot starts, plus an nginx restart.
* There is no CI/CD in any branch: no `.github/`, no `.ebextensions`, no Procfile, no
  Dockerrun (checked across all 7 branches).

### 1.14 Environments

| Environment | How it runs | Configuration | Status |
|---|---|---|---|
| prod | Compose on the VPS | `application.yml`, the server's `config/application-prod.yml` and the server `.env` | Live (inferred). |
| dev (Spring profile) | Developer PC, Windows (logs) | application-dev.yml: RDS URL, EB UAT origin, S3 origins for CORS, committed defaults (17-19, 69-79) | AWS era. The logs show 16 starts with this profile in 2024. |
| local | Profile `local`, `.env.local`, `docker-compose.local.yaml`, `Dockerfile.local`, `local-setup.sh` | `application.local.yml` is never loaded (dot name) | **Broken as committed** (inferred): <br>• compose expects `DB_*` variables and a `docker/Dockerfile` that does not exist (docker-compose.local.yaml:9-12, 29) <br>• `local-setup.sh` expects `docker-compose.local.yml` and containers named `*-local` (local-setup.sh:40-48, 112, 148) |
| test | Profiles `test` (MySQL `localhost:3306/sys`, create-drop) and `h2` (in-memory, `init.sql` seeds 2 users) | src/test/resources/application-test.yml:17,32; application-h2.yml:17-21,36 | Run by hand from the IDE (logs). Skipped in the image build. |
| UAT (AWS) | Elastic Beanstalk `uat-sanchez-logistica`, HTTP | — | Unknown whether it still runs. The frontends' committed `HOST` still points there (brief 02 §2). |

There is no staging environment.

### 1.15 Not in git: the owner must confirm these before the cut-over is planned

These server-side facts decide the cut-over. The owner can collect them with read-only
commands; the agents never see the values.

| What to confirm | How |
|---|---|
| Deployed commit and local changes | `git -C /opt/logistica-sanchez rev-parse --abbrev-ref HEAD`, then `git rev-parse HEAD`, then `git status --short` |
| Running proxy (nginx or Caddy) and active configuration files | `docker ps --format '{{.Names}} {{.Image}} {{.Ports}}'`; `ls nginx/conf.d config/` |
| Variable names only in the production files | `cut -d= -f1 .env`; key names of `config/application-prod.yml` |
| Certificate expiry | `openssl x509 -enddate -noout -in ssl/fullchain.pem` |
| Cron jobs | `crontab -l; ls /etc/cron.d` |
| Backup age and off-site copies | `ls -lh backup/mysql \| tail -3` |
| MySQL version and timezones | `docker exec logistica-mysql mysql -V`, then `SELECT @@version, @@time_zone, @@system_time_zone` |
| Operating system | `lsb_release -a`. Ubuntu 20.04 standard support ended in May 2025. |
| Schema only, for Q-02 | `mysqldump --no-data` |

---

## 2. Stack, versions and health

Versions come from ls@migration:pom.xml, with transitive versions from `mvn dependency:tree`
on a copy of that pom. CVE notes are from memory: **(uncertain)** where marked. "Relevance"
is about this application as written.

| Component | Version (evidence) | Status and known issues |
|---|---|---|
| Java | 17 (pom.xml:17; Dockerfile:2,23 `temurin-17`) | LTS, still supported by Temurin until at least 2027 (uncertain exact date). Two LTS releases behind (21, 25). |
| Spring Boot | **3.2.2** (pom.xml:8) | **OSS support ended November 2024.** Commercial support ended in early 2026 (uncertain). Many patch releases behind. |
| Spring Framework | 6.1.3 (resolved) | Fixed later: CVE-2024-22243, -22259, -22262 (`UriComponentsBuilder`), CVE-2024-38809 (ETag DoS), CVE-2024-38820 (`DataBinder`), and CVE-2024-38816/-38819 (functional static resources; not used here). Mostly low relevance (uncertain). 6.1 OSS support ended in 2025. |
| Spring Security | 6.2.1 | Fixed later: CVE-2024-22234, CVE-2024-22257, CVE-2025-22228 (BCrypt and inputs over 72 bytes), and method-security annotation detection issues in 2025. Relevance uncertain. |
| Tomcat (embedded) | 10.1.18 | Many later CVEs: CVE-2024-24549, -23672, -34750; CVE-2025-24813 (needs a writable default servlet; not the default); CVE-2025-48988 (multipart DoS); HTTP/2 "MadeYouReset". HTTP/2 is off in Tomcat and nginx sits in front, so the DoS risk is mostly mitigated (inferred). |
| Hibernate ORM | 6.4.1.Final | The 6.4 line is out of support. `MySQL8Dialect` is deprecated. |
| Thymeleaf | 3.1.2 | Used for the e-mail and web templates. Acceptable. |
| Jackson | core and databind **2.15.2** (pinned, pom.xml:93-112); Boot-managed modules 2.15.3; `jackson-datatype-jsr310` **2.16.1** | **Mixed versions.** `jsr310` is declared twice (2.15.2 at 113-117, 2.16.1 at 123-127); Maven keeps the last. Fragile. No critical CVE known. |
| Logback | 1.4.14 | CVE-2024-12798 and -12801, CVE-2025-11226. All need control over the configuration: low relevance. |
| MySQL Connector/J 8.3.0, HikariCP 5.0.1, SnakeYAML 2.2, Lombok 1.18.30 | Boot-managed | Old but acceptable. Connector/J CVEs uncertain. |
| jjwt | 0.11.5 (pom.xml:53-66) | Old API; 0.12.x is current. No exploitable CVE known. |
| **AWS SDK for Java v1** | 1.12.341 for SES (69-73); 1.11.792 for S3, KMS, EC2 and CloudFormation, pulled in by Spring Cloud AWS | **End of support 2025-12-31** (maintenance mode since 2024-07-31). Pulls in `ion-java` 1.0.2 (CVE-2024-21634, DoS; not reachable through SES, inferred) and joda-time 2.8.1. |
| `spring-cloud-starter-aws` | **2.2.6.RELEASE** (74-78) | Built for Boot 2 and `javax`; EOL. Still used for `SimpleEmailServiceJavaMailSender` (AwsMailConfig.java:10,29-31). Its auto-configuration does not load on Boot 3 (inferred). IMPROVEMENTS.md's "remove it" (ls@main:IMPROVEMENTS.md:172) needs a code change first. |
| `javax.mail-api` | 1.6.2 (119-122), next to Jakarta Mail 2.0.2 (Angus) | Legacy `javax` namespace. Only needed by Spring Cloud AWS 2. |
| PDFBox | 2.0.24 (132-136) | Outdated. 3.0.x is current. |
| `flying-saucer-pdf` 9.1.22 and `com.lowagie:itext` **2.1.7** | pom.xml:137-146 | iText 2.1.7 dates from 2009 and is abandoned. CVE-2017-9096 (XXE) applicability is uncertain. These libraries pull in BouncyCastle `bcprov-jdk14` 1.64 and the 2007-era `bouncycastle:bcprov-jdk14:138`, which carry known CVEs. Two copies of BouncyCastle sit on the classpath. Practical relevance is low (inferred), but scanners will flag them. |
| MySQL server | **8.0.35** (docker-compose.yml:6) | **MySQL 8.0 reached EOL in April 2026** (Oracle lifecycle; verify). 8.0.35 is the October 2023 release, so more than two years of security fixes are missing. The patch version is pinned, so the image is never updated. |
| nginx | `nginx:alpine`, unpinned | Current as of the last image pull. `docker compose up` does not pull new images. |
| Base images | `maven:3.9-eclipse-temurin-17-alpine`, `eclipse-temurin:17-jre-alpine` (floating tags) | Up to date whenever the image is rebuilt. |

**Implication for Gate 0 (brief 10 §1, Path A criteria).** One criterion is that the
framework and runtime are still supported. Spring Boot 3.2 fails it. Upgrading to a
supported line is feasible, but it means:
* replacing Spring Cloud AWS 2 and SDK v1 with SDK v2 (SESv2);
* replacing the iText 2.1.7 PDF path (for example with OpenPDF or flying-saucer-openpdf);
* dropping `javax.mail`;
* aligning Jackson with the Boot BOM;
* adding actuator (health only);
* adding a Flyway baseline for the schema Hibernate created.

Record this in ADR 0001.

---

## 3. Repository hygiene and security findings

Severity reflects that the repo is private: it limits who can see the data, not the damage.
"Now" means before any cut-over work starts.

| ID | Severity | Finding | Evidence | Action now |
|---|---|---|---|---|
| F-01 | **Critical** (live personal-data exposure) | **EP-09 `POST /mono/api/order/pending` has no authorization**, so anyone can list all pending orders with customer data. **EP-18 `POST /mono/cms/order/report`** is unguarded too, so anyone can trigger report e-mails. The URL rule for `/mono/cms/**` probably never matches, because Spring Security matches paths without the `/mono` context path (inferred). Protection therefore rests on `@PreAuthorize` alone. nginx adds no restriction. | ls@migration:…/api/controller/OrderController.java:55-59 (no `@PreAuthorize`); CmsController.java:79-80 (none); WebSecurityConfig.java:41-42 (`/mono/cms/**` then `anyRequest().permitAll()`); nginx/conf.d/app-https.conf:54-74 | 1. Add `@PreAuthorize(MonoConstants.ADMIN_PRE_AUTH)` to both handlers and redeploy by hand. The CMS already sends a Bearer token, so nothing breaks. <br>2. Until then, add a stopgap in nginx: an exact-match `location` for each of the two paths that allows only the office IP, or that returns 401 when there is no `Authorization` header. <br>3. Then follow the steps of Q-21: export `https_access.log` from the `nginx_logs` volume for these paths, and run the GDPR assessment with an adviser. <br>4. Confirm with probe P-04 (status and size only). |
| F-02 | **High** (privacy) | **Committed logs contain personal data, plaintext passwords and JWTs** (§3.1). They are on `migration` only, added in ls@2531356 and ls@285d2b1. | ls@migration:logs/** (21 files) | 1. Remove `logs/` and add it to `.gitignore`. <br>2. Purge it from history with `git filter-repo` and force-push the `migration` branch. Ask GitHub Support to purge cached views if needed. Delete other clones and copies, including AI session scratch copies. <br>3. Change the staff passwords now, since a logged password may be real. <br>4. Record the incident in the GDPR register. <br>5. Fix the cause: log only method, path and status, and purge or limit the production log volume (F-10). |
| F-03 | **High**, to verify | **A format-valid AWS key pair ("pair B") is committed as a default** in 4 files. It has been in history since 2024-02-09 (ls@2ce6b43) on every `dev_*` branch and on `migration`. **It is not the pair the owner declared synthetic.** That pair ("A") appears only in `main`'s first commits. A local-profile run on 2024-07-18 logged an SES "security token … invalid" error, which suggests pair B was already invalid then (inferred, weak). | ls@migration:src/main/resources/application-dev.yml:75-76; application.local.yml:75-76; src/test/resources/application-test.yml:75-76; application-h2.yml:79-80; ls@claude/fable-repository-improvements-8auxl1 commit 4bd76b4 (statement about pair A) | 1. In IAM, look up the key ID at application-dev.yml:75 (the "last used" field); delete it if it exists, and check CloudTrail. <br>2. Make sure production uses a different key with an SES-only policy. <br>3. Remove all `${AWS_*:default}` defaults. <br>4. Purge history together with F-02. |
| F-04 | **High**, conditional | **A default JWT signing key is committed** (30 characters, 4 files). The `.env` value is a placeholder. The raw configured string is used as the HMAC key. If the server `.env` reuses either value, anyone with repo access can mint ADMIN tokens. | application-dev.yml:71; application.local.yml:71; application-test.yml:71; application-h2.yml:75; .env:11; …/security/JwtService.java:41 | Check that the server's `JWT_KEY` is a fresh random value of at least 256 bits that appears nowhere in git, and rotate it now. The only effect is that staff must log in again. |
| F-05 | **High** (latent) | **The server's work tree holds secrets and data, and git tracks or does not ignore them.** `.env` is tracked. `.gitignore` covers none of `.env`, `ssl/`, `backup/`, `config/` or `logs/`. On the VPS the repo is `/opt/logistica-sanchez` itself, so a single `git add -A && git commit && git push` there would publish the production `.env`, the TLS private key and full database dumps (inferred). | ls@migration:.gitignore:1-33; docker-compose.yml:99,130; scripts/backup.sh:34; scripts/ssl-setup.sh:12 | 1. `git rm --cached .env .env.local`, add `.env*`, `ssl/`, `backup/`, `config/` and `logs/` to `.gitignore`, and keep an `.env.example`. <br>2. Never commit from the server. |
| F-06 | **Medium** | The **Postman collection holds three login bodies with passwords**. One of them matches the password in 7 of the logged logins. | ls@migration:api/sanchez-logistica.postman_collection.json:38,65,155 | Change those accounts' passwords if they are real, and use Postman variables instead. |
| F-07 | **Medium** (probably synthetic) | **A default database password is committed** (dev, local and test configs). It is the same value as `main`'s, which the owner declared synthetic. RDS host names are committed too. | application-dev.yml:17-19; application.local.yml:19; application-test.yml:19; ls@1c65922:src/main/resources/application.yml:8-10 | Confirm the RDS instances are deleted, or not publicly reachable with a rotated password, and remove the defaults. |
| F-08 | **Medium** (availability) | **TLS renewal is probably broken**: the standalone certbot run conflicts with nginx on :80, and the certificate is never copied again (§1.5). The private key is world-readable on the host. | scripts/ssl-setup.sh:56-63,75-78,194-197 | 1. Check the expiry now. <br>2. Fix renewal: <br>• webroot with `/var/www/certbot` mounted, plus a deploy-hook that copies the files and reloads nginx; <br>• or mount `/etc/letsencrypt` directly; <br>• or switch to Caddy. <br>3. `chmod 600` the key. |
| F-09 | **Medium** (data) | **Backups are local, unencrypted, kept 7 days, include `.env`, and restores are untested** (§1.11). | scripts/backup.sh:9,68-83,123-166 | Before any migration work, set up encrypted off-site backups, run a test restore, and restrict permissions on `backup/`. |
| F-10 | **Medium** (privacy and operations) | **The VPS keeps logs with personal data and no size limit**: no `maxHistory` in logback, body logging on, nginx log files never rotated. Over time the disk fills, and retention breaks GDPR. | logback.xml:13-29; RequestResponseLoggingFilter.java:37-45; app-https.conf:50 | Set `maxHistory` and `totalSizeCap`, stop logging bodies, rotate nginx logs, and align retention with Q-18. |
| F-11 | **Medium** (operations) | **`deploy.sh` is unsafe**: it causes a merge conflict and cannot scale. There is no health endpoint, and the monitoring is ineffective (§1.9, §1.12, §1.13). | scripts/deploy.sh:55,108; Dockerfile:71-73 | Do not run `deploy.sh`. Deploy by commit, by hand. Add a trivial health mapping, or actuator with health only. |
| F-12 | **Medium** (security) | **Production nginx does no rate limiting.** It existed only in the deleted pre-TLS file. Login brute force is not throttled (inferred). | nginx/conf.d/logistica.conf:8-9,23,37 versus app-https.conf | Add `limit_req` for `/mono/auth/` and the public write endpoints in `app-https.conf`. |
| F-13 | **Medium** (exposure waiting to happen) | **The management configuration exposes `'*'`, `env` and `configprops`**, and `fix-actuator.sh` advises `include: '*'`. Adding actuator one day would publish them (§1.9). | application.yml:59-66; application.prod.yml:79-93; scripts/fix-actuator.sh:215-224 | Delete those blocks, or set `include: health` with `show-details: never`. |
| F-14 | **Medium** (configuration drift) | **The production configuration lives outside git.** The dot-named files are never loaded, the `EMAIL_ORIGIN` / `EMAIL_SENDER_ORIGIN` names do not match, and the CORS template omits `ordini.` and `dashboard.` (§1.6). | docker-compose.yml:83,99; .env:21,25 | 1. Document the real key names. <br>2. The owner checks the host in the links of a recent e-mail and the production CORS list. |
| F-15 | Low | **Two CORS settings are wrong:** <br>• `allowCredentials(true)` is not needed with Bearer tokens; <br>• the dev defaults include plain-HTTP S3 origins and bare host names, which never match a real `Origin` header. | WebSecurityConfig.java:63; application-dev.yml:69 | None for the legacy system. The new app is same-origin. |
| F-16 | Low | **Plain-HTTP endpoints remain** in the dev and UAT settings. The Elastic Beanstalk UAT may still be up, over HTTP, with its own data. | application-dev.yml:69,79 | Check it for personal data, then stop it (brief 08 §4.9). |
| F-17 | Low | **Minor server hygiene issues:** <br>• the debug port is mapped; <br>• the MySQL root password appears on command lines (healthcheck and scripts), so `docker inspect` and `ps` show it; <br>• images are unpinned (`nginx:alpine`, `:latest`); <br>• docker-compose is downloaded as "latest" from GitHub; <br>• the compose `version:` key is obsolete. | docker-compose.yml:28,94; scripts/server-setup.sh:45,158-159 | Tidy these the next time the server is touched. |
| F-18 | Low (hygiene) | **The repository is badly organised:** <br>• the default branch `main` is not production, and 5 stale branches remain; <br>• the README is a generic template that points to missing files (`.env.example` at README.md:33, `scripts/status.sh` at README.md:465) and to `your-repo`/`your-domain` placeholders (README.md:20,28); <br>• the `local-setup.sh` file names are wrong; <br>• there is no CI, and tests are skipped in the build; <br>• `local-setup.sh` prints a local database password (local-setup.sh:112,145). | §4; Dockerfile:17 | After the purge, make the deployed branch the default, tag and delete the stale branches, and mark `IMPROVEMENTS.md` as an analysis of `main` only. |
| F-19 | Low to medium | **The stack is end-of-life** (§2). | pom.xml; docker-compose.yml:6 | Decide in ADR 0001. Do not patch the legacy system beyond F-01 to F-12. |

### 3.1 The committed logs, in aggregate (no values copied)

* **Location and size**: `ls@migration:logs/spring-boot-logger.log` plus 20 files under
  `logs/archived/`. About 94,000 lines and about 11 MB. The archive dates run from
  2024-06-12 to 2025-01-07.
* **Origin**: a developer PC running Windows with Java 17.0.8; 184 startup lines contain
  Windows paths. The active profiles were `test` (91 starts), `local` (42), `h2` (18) and
  `dev` (16), plus 3 starts with no profile. The `dev` profile connects to RDS by default.
  **These are not production server logs**, but some sessions ran against the AWS-era
  database (inferred).
* **Contents**, mostly from `RequestResponseLoggingFilter` (748 lines):
  * about 160 customer or order JSON bodies, with company name, address, city, postcode,
    phone and e-mail: 84 in `local` sessions, 66 in `test`, 10 in `dev`;
  * 617 e-mail address occurrences, 7 distinct: 4 on `gmail.com` and 3 on two Italian
    business domains;
  * about 130 phone-like strings, 11 distinct;
  * about 1,000 address-like strings, 7 distinct;
  * 21 numbers of 11 digits, 3 distinct, possibly VAT numbers;
  * **8 login requests with plaintext passwords** (2 distinct passwords, 1 username), in
    `spring-boot-logger-2024-06-30.0.log` and `-2024-08-26.0.log`;
  * **18 login responses with access and refresh JWTs.** They are expired now, since
    access tokens last 6 h, but they reveal usernames and roles;
  * one report e-mail in HTML whose unauthenticated download link carries a customer
    e-mail address;
  * 228 lines that reveal the developer's Windows user profile path.
* **Not found** by pattern search: AWS keys, database passwords, RDS host names.
* **Exposure**: the repo is private with 0 forks. Everyone with read access and every clone,
  including AI-session copies, holds the data.

### 3.2 Inventory of secret-shaped values (labels only)

| Label | Where | Since | Assessment |
|---|---|---|---|
| AWS pair "A" (an AKIA-format ID and a 40-character secret) | ls@cad642c and ls@1c65922:src/main/resources/application.yml:28-29 (deleted from the tip of `main` in ls@7dbb2d4) | 2024-02-03 | The owner declared it synthetic (ls@claude/fable-repository-improvements-8auxl1:IMPROVEMENTS.md:31-32; commit 4bd76b4). Hygiene only. |
| AWS pair "B" | ls@migration:application-dev.yml:75-76; application.local.yml:75-76; test application-test.yml:75-76 and application-h2.yml:79-80; `application.yml` in the history of the `dev_*` branches | 2024-02-09 (ls@2ce6b43) | **Not covered by the owner's statement.** Verify in IAM (F-03). |
| Database password default (20 characters) | application-dev.yml:19; application.local.yml:19; application-test.yml:19 | 2024-02-03 | The same value as `main`'s synthetic one (F-07). |
| JWT key default (30 characters) | application-dev.yml:71; application.local.yml:71; application-test.yml:71; application-h2.yml:75 | 2024-02-29 (ls@6187a37) | Not covered by the owner's statement (F-04). |
| `.env` | .env:2,5,11,16,17 | 2025-10-03 | Template placeholders of the "your…here" kind. Not live, unless they were copied unchanged to the server. |
| `.env.local` and `local-setup.sh` | .env.local:4,7,14,19-20; local-setup.sh:59-71,112,145 | 2025-10-03 | Values meant for local use only. Low. |
| Postman login bodies | api/sanchez-logistica.postman_collection.json:38,65,155 | 2024-02-24 (ls@a38063b; added again in ls@0f1a1fe) | One password matches the logs (F-06). |
| Logged passwords and JWTs | logs/archived/… (2 files) | 2025-10-03 | F-02. |
| Test seed users (bcrypt) | src/test/resources/init.sql (2 rows) | — | Test only. |

---

## 4. Branch map

### 4.1 Branches

| Branch | Tip (date) | Commits | Files | What it is | Status |
|---|---|---|---|---|---|
| `main` | ls@7dbb2d4 (2026-07-29) | 5 | 30 | The skeleton of 2024-02-03: a prototype `/mono/spedizioni` CRUD and the trait model. On 2026-07-29 it gained `IMPROVEMENTS.md` (PR #1, merge ls@4c60054) and lost `application.yml`, deleted by the owner (ls@7dbb2d4). | GitHub default branch. **Not production**, and it cannot run without `application.yml` (inferred). |
| `dev_thymeleaf` | ls@2ce6b43 (2024-02-09) | 3 | 57 | The first "setup", with Thymeleaf. Introduces AWS pair B. | Stale ancestor of `migration`. |
| `dev_API` | ls@5c76ad2 (2024-02-10) | 8 | 61 | REST API setup, JPA, RDS and SES configuration. | Stale ancestor. |
| `dev_API_fullSQL` | ls@b2941cc (2024-02-13) | 9 | 64 | Adds "Full SQL: might need in future". | Stale ancestor; it lies on the `migration` line. |
| `dev_API_v2` | ls@91cf025 (2025-01-07) | 71 | 189 | The complete backend v0.0.2 of the AWS Elastic Beanstalk and RDS era: 63 commits from 2024-02-13 to 2025-01-07. | Stale ancestor. Probably what ran on Elastic Beanstalk (inferred). |
| **`migration`** | **ls@ca1b0d0 (2025-11-16)** | 74 | 230 | `dev_API_v2` plus the move to Docker on a VPS (3 commits). | **Production (inferred).** |
| `claude/fable-repository-improvements-8auxl1` | ls@4bd76b4 (2026-07-29) | 4 | 31 | An AI analysis of `main`. Commit ls@9a202ca was merged as PR #1. Commit ls@4bd76b4, which downgrades P0 because "committed credentials are synthetic" according to the owner, was **not merged**. | Documentation only. |

Lineage (all linear): `cad642c → 1c65922 → 2ce6b43 (dev_thymeleaf) → … → 5c76ad2 (dev_API)
→ b2941cc (dev_API_fullSQL) → … → 91cf025 (dev_API_v2) → 2531356 → 285d2b1 → ca1b0d0
(migration)`. `main` and the claude branch fork at `1c65922` and add only documentation.
Every `dev_*` branch is an ancestor of `migration`.

### 4.2 What `migration` changed relative to `dev_API_v2`

`git log origin/dev_API_v2..origin/migration` lists three commits, all by FrancescoCorbosiero:

| Commit | Date | Message | Content |
|---|---|---|---|
| ls@2531356 | 2025-10-03 | "Stable migration version - Dockerized - Ready for VPS hosting tests" | Adds `.env`, `.env.local`, `Dockerfile`, `Dockerfile.local`, `README.md`, both compose files, `local-setup.sh`, `logistica.conf`, scripts (backup, deploy, monitoring, server-setup, ssl-setup), two test JSON schemas, **and 21 log files**. 36 files, +94,838 lines. |
| ls@285d2b1 | 2025-10-20 | "Stable - Migration complete" | <br>• Health URLs move from `/mono/actuator/health` to `/mono/`; the Docker HEALTHCHECK is commented out. <br>• Compose gets `JAVA_OPTS`, `SPRING_PROFILES_ACTIVE`, `EMAIL_ORIGIN` and the debug port; the slow-query log is removed. <br>• Adds `nginx.conf`, `app-https.conf`, `fix-actuator.sh` and `application.prod.yml`; rewrites `ssl-setup.sh`. <br>• Renames `application-local.yml` to `application.local.yml`. <br>• Adds one more log. <br>13 files. |
| ls@ca1b0d0 | 2025-11-16 | "http ses fix" | `LinkUtil` only: e-mail links switch to `https://`; S3 portal and CMS links become `ordini.` and `dashboard.`. |

Overall diff stat: 43 files, +96,790 / −6. More than 94,000 of those lines are logs.

**The migration changed the hosting, not the application:**
* hosting went from Elastic Beanstalk and RDS MySQL in eu-north-1 to a VPS running Docker
  Compose with MySQL 8.0.35 in a container (README.md:241-278);
* `pom.xml` and all Java code are unchanged except `LinkUtil`;
* SES and SDK v1 are still used;
* no health endpoint and no schema migrations were added.

### 4.3 Which branch is production

Evidence for **`migration` @ `ca1b0d0`** (inferred, high confidence):
1. Its nginx, certificate and monitoring all target `app.logisticasanchez.com`, which is the
   production base URL the owner confirmed (brief 02 §2) (app-https.conf:28; ssl-setup.sh:8;
   monitoring.sh:108).
2. Its commit messages declare the move complete (285d2b1). A later fix (ca1b0d0) changes
   production e-mail links to the live hosts `ordini.` and `dashboard.`.
3. It is the newest code in the repo. No branch has newer code; only documentation commits
   on 2026-07-29.
4. `main` lacks every endpoint the frontends call. The 2026-07-29 analysis concluded that
   the deployed code had to exist outside `main`, and it does: on `migration`
   (ls@main:IMPROVEMENTS.md:245-266; ls@claude/fable-repository-improvements-8auxl1:IMPROVEMENTS.md:273).

Caveats:
* The server may run local modifications: `config/application-prod.yml`, `.env`, nginx
  configuration, or even Caddy (ls@main:IMPROVEMENTS.md:5).
* The running image may not be exactly `ca1b0d0`.

The owner can check both with the commands in §1.15.

### 4.4 How far to trust `IMPROVEMENTS.md`

It analysed `main` only, which has almost no code. Still valid:
* the platform EOL and upgrade advice (ls@main:IMPROVEMENTS.md:166-174);
* moving to versioned migrations and `validate`;
* `BigDecimal` for money;
* actuator with health only;
* secret externalisation.

Obsolete or wrong for production:
* "nothing for deploy in the repo" (76): `migration` has Docker, compose and scripts;
* "the endpoints the frontends call do not exist" (245-259): they exist on `migration`;
* "the Thymeleaf starter is unused" (173): the e-mail templates use it;
* "spring-cloud-aws inert, remove" (172): the mail sender uses it;
* the "Caddy" infrastructure (5): not visible in any branch.

The owner's verification that the credentials were synthetic covers only `main`'s `application.yml`.

---

## 5. What `ls-web` is

* **Repository**: `FrancescoCorbosiero/ls-web`, reported public by the owner. The GitHub API
  is not enabled for this repo in this session, so I could not confirm the visibility.
  * One commit, ls-web@abe31e4 "First commit", dated 2026-10-16 11:08 +0200, authored by
    FrancescoCorbosiero.
  * **One file**: `PROMPT.md` (128 lines, 11,896 bytes).
  * The pack holds 3 objects; there is no other history.
* **Content**:
  * An Italian prompt template for Claude Code to build a static showcase or event site "in
    the style of marketersworld.net" (ls-web@abe31e4:PROMPT.md:1). Placeholders such as
    `[NOME PROGETTO]` must be filled in (3, 10, 32).
  * Section A is a `CLAUDE.md` with these rules: Astro with static output, pure CSS tokens,
    vanilla JS, no extra npm dependencies, and Lighthouse of at least 90 (13-16, 24).
  * Section B is a phased build plan: hero, pricing, speakers, FAQ, countdown, checkout CTA
    (36-118). Publishing goes to Netlify, Vercel, Cloudflare Pages or GitHub Pages (117).
  * The usage notes describe the reference site as a Vite and React SPA (124).
  * **It mentions nothing about Logistica Sanchez**: no logistics, pallets, tracking or
    backend.
* **Sensitive content: none.** No secrets, personal data or internal URLs; only the commit
  author's identity, which is public on GitHub anyway.
* **Relation to the brief**: none directly.
  * It is not the production site. It is not a repository for the monolith. It holds no
    legacy code.
  * The name "ls-web" may signal a plan to rebuild the public site as a separate static site
    (inferred, weak). That would conflict with the brief:
    * the monolith is supposed to render the public pages (01 principle 2, brief 01:94-95;
      10 §2 "Public site inside the app");
    * the brief targets Lighthouse 95, not 90 (01:111).
  * More likely it is a generic template for another project: events, courses, speakers,
    checkout (inferred).
* **Action**: ignore it for the monolith unless the owner says otherwise. This is open
  question Q-51 in §6.7.

---

## 6. Corrections to the brief

### 6.1 Chapter 01: the as-is description

* **"Sources" paragraph** (brief 01:7-12). Replace *"The legacy backend source code and
  database are not part of this brief"* with:
  > "The legacy backend source is available: the private repository
  > `FrancescoCorbosiero/ls`, production branch `migration` (`ca1b0d0`, 2025-11-16; Java 17,
  > Spring Boot 3.2.2, MySQL 8.0.35). Its database is a MySQL container on the production VPS,
  > so the owner can provide a dump. They are the primary reference for parity (chapter 10,
  > assess Path A)."
* **Diagram, backend box** (01:47-50). Replace `(AWS EB)` and "source not available; sends
  e-mail (owner uses SES)" with:
  > "Contabo VPS, Docker Compose (nginx + Spring Boot 3.2 + MySQL 8.0) since October 2025;
  > before that AWS Elastic Beanstalk + RDS in eu-north-1. Source: `ls@migration`. E-mail
  > through AWS SES eu-north-1 with a static IAM key."
  
  Add that the database sits in the same box: `lsdb`, local volume.
* **Diagram, CMS box**: add the host **`dashboard.logisticasanchez.com`**, taken from the
  backend's e-mail links (ls@migration:…/LinkUtil.java:28-30). Hosting is unknown (Q-47).
* **Backend bullet** (01:67-68): add two facts.
  * JWT protects only methods annotated `@PreAuthorize`. EP-09 and EP-18 are open (Q-21).
  * Every request and response body is logged.
* **Public site bullet**: the backend's delivery e-mail links to
  `https://www.logisticasanchez.com/#tracking` (templates/tracking-state.html:48). That
  anchor needs to keep working (see Q-29).
* **New short paragraph, "Legacy infrastructure"**: summarise §1.1 to §1.3 of these notes.
  State that production configuration and backups live only on the VPS (§1.15).

### 6.2 Chapter 10 §3: infrastructure defaults the new app should assume

The brief's default (10:168-183) is Elastic Beanstalk, RDS PostgreSQL, ACM, Route 53,
Secrets Manager with an instance role, and CloudWatch. It contradicts the owner's own
decision in October 2025 to leave Elastic Beanstalk and RDS for a single VPS. **The new
default should be a single EU VPS with Docker Compose; AWS only for SES, and optionally S3.**
Managed AWS remains an alternative if the owner asks for it. Proposed replacement table:

| Piece | Default (confirm at Gate 0) |
|---|---|
| Hosting | One EU VPS from the provider the owner already uses (Contabo; ls@migration:scripts/server-setup.sh:10), on a supported Ubuntu LTS. **Put the new app on a new VPS**, so the legacy server stays intact for rollback. Share the legacy VPS only if cost forces it; one reverse proxy would then route by host name. |
| Data location | An EU data centre. Contabo's data-processing agreement signed. SES stays in eu-north-1, where the legacy backend already sends from (AwsMailConfig.java:25). |
| Containers | `proxy`: Caddy 2 with automatic HTTPS, or nginx with certbot and a renewal that works (unlike F-08). `app`: the monolith image, non-root, with a healthcheck on the health endpoint. `db`: PostgreSQL, current major, or MySQL 8.4 LTS if ADR 0001 keeps MySQL under Path A. Only the proxy publishes ports (80/443); `app` and `db` sit on an internal network. Remember that Docker bypasses UFW. |
| Database | A container with a named volume. Flyway. Nightly logical dump plus a provider snapshot. A tested restore. |
| Files | A private S3 bucket as in the brief (documents, reports, pre-signed downloads). Or a local volume included in the backups, if the owner does not want S3. |
| E-mail | SESv2 with a **dedicated IAM user** (a VPS has no instance role). Least privilege: `ses:SendEmail` with an `ses:FromAddress` condition, receive and delete on the bounce queue, its own bucket only. The key lives only in the server's secret file and is rotated at least yearly. Reuse the domain identity already verified in eu-north-1 (inferred). |
| Secrets | A server-side `.env` owned by root with mode 0600, or Docker secrets. Never in git: `.gitignore` guards plus gitleaks in CI. A redacted `.env.example` documents the keys. |
| TLS and DNS | A/AAAA records for the apex, `www`, `app.`, `ordini.` and `dashboard.`, pointing to the VPS IP at the current DNS provider. No Route 53 or ALIAS needed. Let's Encrypt, either DNS-01 to pre-issue or HTTP-01 right after the switch. HSTS. |
| Deploy | CI builds a versioned image and pushes it to a private GHCR. On the server: `docker compose pull && docker compose up -d`, with manual approval for production. Flyway runs at startup. Rollback means the previous image tag plus a database restore point. The proxy has a maintenance page. |
| Logs and alerts | Docker json-file logs with rotation. Application logs without bodies or personal data, kept at most 30 days. An external uptime and TLS-expiry check on the health URL. Disk-space and backup-age alerts by e-mail through SES. |
| Backups | Nightly database dump plus files, encrypted with restic, borg or age, sent to off-site object storage in the EU (S3 eu-north-1 or the provider's own). Keep 7 daily, 4 weekly and 12 monthly copies (the owner may change this; Q-18). Test a restore monthly. Alert when the newest backup is older than 26 h. |
| Environments | Local: Docker Compose, a mail catcher, SES and S3 fakes. Staging: a second compose project on the new VPS behind basic auth or an IP allow-list, with its own database holding the anonymised copy and the e-mail guard of the brief. Production. |
| Hardening | UFW for 22, 80 and 443; SSH keys only; no root login; unattended-upgrades; fail2ban for sshd. |
| Infrastructure as code | A `deploy/` folder in the repo with compose files, proxy configuration, a server bootstrap script and the runbook. Terraform is optional. |

Keep the existing rows on staging e-mail and on staging data and access (10:181-182)
unchanged.

### 6.3 Knock-on edits elsewhere in chapter 10

* **§4, rate limits** (10:198). Replace "as written by the AWS load balancer" with "as written
  by the reverse proxy on the server (trust exactly one hop)".
* **§4, IAM** (10:221-222). Replace "the app role" with "a dedicated IAM user with the same
  least-privilege policy; its key lives only in the server's secret file".
* **§4, health endpoint** (10:219-220): keep it. Add: "the legacy system carried
  `exposure '*'` and `env`/`configprops` settings that were harmless only because actuator
  was missing (§1.9 of 12d)".
* **§1, Path A** (10:21-22). Make the instruction explicit. Import only `src/main` and the
  templates. Leave out `logs/`, `api/` (the Postman collection), `.env*`, and the test
  configurations that carry keys. Import no history.
* **§5, processors** (10:229-232): Contabo for hosting, database and backups; AWS for SES,
  and S3 if used.

### 6.4 Chapter 10 §7: cut-over (edits to each step)

1. **Weeks before** (10:267-269):
   * Drop "move the DNS zone to Route 53". It is not needed for a VPS with a static IP.
   * Lower the TTLs on the apex, `www`, `app.`, `ordini.` **and `dashboard.`**, plus any
     other record that points to the old VPS or to AWS.
   * Provision the new VPS.
   * Certificates: DNS-01 to pre-issue them, or plan an HTTP-01 issue at the switch.
   * SES: reuse the verified identity and create a new IAM key.
   * Take a legacy backup and **test-restore it** (F-09).
2. **Freeze** (10:270-272). Replace "block the legacy write endpoints at the infrastructure
   level" with this:
   * On the legacy VPS, add an nginx snippet that answers 503 or 410 for:
     * EP-03, EP-06, EP-07, EP-08, EP-11, EP-12, EP-14, EP-16 and EP-18;
     * `/mono/utils/**` and `/mono/sendEmail`.
   * Keep EP-13, EP-19 and EP-20 open. The README already has a maintenance recipe
     (README.md:377-408).
   * Then run `docker exec logistica-nginx nginx -s reload`.
   * Alternatively run `docker compose -p logisticasanchez stop app`; MySQL keeps running for
     the dump, but tracking is down meanwhile.
   * The app is reachable only through that nginx (ports bound to 127.0.0.1;
     docker-compose.yml:93), so this stops every caller, whatever the host name (inferred).
   * Also stop any AWS-era backend that could still accept orders (Q-46).
3. **Final import** (10:273-274). Take a dump inside the legacy container, as `backup.sh`
   does (`mysqldump --single-transaction --routines --triggers --events --databases lsdb`;
   scripts/backup.sh:42-50), and transfer it encrypted. Option 1 (a dump) is now the obvious
   path.
4. **DNS** (10:275-276). Add `dashboard.logisticasanchez.com` → 301 to `/gestione/`. Use
   A/AAAA records, not aliases.
   * `app.` must also answer the old e-mail-link paths: `/mono/email-confirm`,
     `/mono/report`, `/mono/download-report`, and `/mono/img/logistica-sanchez-logo.jpg`
     (see Q-30).
5. **Smoke tests**: add one old e-mail link and the logo URL.
6. **Rollback** (10:278-280). Point DNS back to the legacy VPS IP and remove the nginx block,
   or start the app container. Leave the legacy server untouched during the window, and
   **never run `docker compose down -v` on it**: `-v` deletes the `mysql_data` volume
   (docker-compose.yml:17-18, 147-149).
7. **After** (10:281-285):
   * Export the DNS zone. Remove or repoint every record that points to the old VPS IP
     **before cancelling the VPS**: once the provider reassigns the IP, a dangling A record
     lets someone else's server answer for our subdomain. Do the same for the AWS hosts.
   * Archive a final encrypted dump.
   * Securely delete the legacy backups (full dumps and `.env` copies) and the log volumes,
     according to Q-18.
   * Delete the legacy SES key, and pair B if it exists.
   * Delete the Elastic Beanstalk environments, the RDS instances and their snapshots after
     checking them for personal data. Keep the S3 names as redirects.
   * Retire Netlify (Q-20) and rotate the staff passwords.
   * Archive the legacy GitHub repo after purging logs and secrets.

### 6.5 Chapter 08 §4.7–4.9 (and §4.2)

* **§4.2, step 1** (08:831). The legacy engine is **MySQL 8.0.35** and the database is
  `lsdb`. Restore the dump into an isolated MySQL 8.0 container. Nightly dumps may already
  exist under `/opt/logistica-sanchez/backup/mysql/` (scripts/backup.sh:40). Check for the
  October 2025 timezone break before mapping timestamps (§1.7).
* **§4.7, "Before"** (08:987-990):
  * The names whose TTLs must be lowered, and that need certificates, are the apex, `www`,
    `app.`, `ordini.` **and `dashboard.`**: five, not four.
  * On a VPS, Let's Encrypt replaces ACM's DNS validation. Use DNS-01, or issue right after
    the switch.
  * SES production access already exists, because the legacy backend e-mails customers
    (inferred). Only a new key is needed.
* **§4.7, "Freeze and switch", step 2** (08:1004-1013):
  * Replace "at the load balancer or Elastic Beanstalk level" with "in the legacy VPS's nginx
    (`nginx/conf.d`, then reload)". Path rules are possible there, so the fallback "stop the
    old backend" is rarely needed.
  * Delete *"The backend source is not available…"*. It is available now, but the switch at
    the infrastructure level is still the simplest.
  * Check whether any AWS-era environment still serves a frontend. A portal still pointed at
    Elastic Beanstalk would write into the old RDS, and those orders would be lost (Q-46,
    Q-47).
* **§4.7, step 6, DNS** (08:1021-1027): add `dashboard.logisticasanchez.com` → 301 to
  `https://logisticasanchez.com/gestione/`.
* **§4.8, rollback, step 3** (08:1054): "the legacy hosts" means the legacy VPS IP for
  `app.`. The legacy nginx block is lifted by removing the snippet. Never run
  `docker compose down -v`. Do not use `scripts/deploy.sh` during the window (F-11).
* **§4.9, "After"** (08:1065-1078). Extend the list of old resources:
  * the Contabo VPS and its IP (dangling A records);
  * the AWS-era RDS instances `sanchez-logistica-db-1` and `db-logistica-sanchez-1`, and
    their snapshots;
  * the Elastic Beanstalk UAT environment `uat-sanchez-logistica`;
  * the IAM keys: the legacy backend's SES key in the server `.env`, and the committed pair B
    if it exists;
  * the monitoring containers and cron jobs on the VPS;
  * the backups and logs on the VPS, which hold personal data and secrets: delete them
    securely, and only after archiving the final dump.

### 6.6 Answers from the repo

* **Q-16 (hosting and DNS)**: partly answered.
  * The backend no longer runs on AWS compute. It runs on a **Contabo VPS (Ubuntu) with
    Docker Compose** at `/opt/logistica-sanchez` (server-setup.sh:10; README.md:6;
    docker-compose.yml).
  * AWS is still used for **SES in eu-north-1** (AwsMailConfig.java:25).
  * Before October 2025 the backend ran on Elastic Beanstalk and RDS in eu-north-1
    (application-dev.yml:17,79).
  * `app.` must point to the VPS (inferred from the certbot HTTP-01 run).
  * Not in the repo: who manages DNS, whether the zone is on Route 53 (not needed any more),
    and how the apex, `ordini.` and `dashboard.` are hosted.
  * New fact: the CMS host is `dashboard.logisticasanchez.com` (LinkUtil.java:28-30).
  * New default: same provider model (§6.2), with the new app on a new VPS.
* **Q-19 (cut-over window)**: not answered by the repo. Relevant constraints:
  * avoid the 02:00 backup and the 03:00 certbot jobs (README.md:50; ssl-setup.sh:196);
  * `mysqldump --single-transaction` needs no downtime;
  * a maintenance-mode recipe exists (README.md:377-408).
  
  Keep the brief's default of a Saturday afternoon, under one hour.
* **Q-20 (retire Netlify)**: not answered. The backend repo has no Netlify reference. Note
  that `ls-web`'s prompt targets Netlify, Vercel, Cloudflare Pages or GitHub Pages for a new
  static site (ls-web@abe31e4:PROMPT.md:117); see Q-51.
* **Q-21 (are staff reads open?)**: **yes, for EP-09** (inferred from code; confirm with
  P-04).
  * EP-04 and EP-05 (CustomerController.java:41-49), EP-10 and EP-17
    (OrderController.java:61-62,80-81) and EP-15 (CmsController.java:125-126) carry
    `@PreAuthorize("hasRole('ADMIN')")`.
  * **EP-09 carries nothing** (OrderController.java:55-59).
  * **EP-18 carries nothing** (CmsController.java:79-80). It is a write: it sends e-mails.
  * The URL rule is ineffective (WebSecurityConfig.java:41) (inferred).
  * Apply Q-21's actions today: the nginx stopgap or the one-line fix, export the access logs
    from `nginx_logs`, and run the GDPR assessment (F-01).
* **Q-29 (old URLs and hosts)**: partly answered. E-mails already in customers' inboxes link
  to these addresses, which need redirects or answers:
  * `https://{EMAIL_ORIGIN}/mono/email-confirm?…`, `/mono/report?…`,
    `/mono/download-report?…`, and `/mono/img/logistica-sanchez-logo.jpg` (the logo in
    every e-mail);
  * `https://ordini.logisticasanchez.com/` and `https://dashboard.logisticasanchez.com/`;
  * `https://www.logisticasanchez.com/#tracking`. The fragment survives a 301 from `www` to
    the apex, so the new home page must handle `#tracking`;
  * from e-mails sent before 2025-11-16: the `http://` forms of the same paths, the two S3
    website hosts, and the Elastic Beanstalk UAT origin.

  The owner must say which host `EMAIL_ORIGIN` was in production, the apex or `app.`
  (Q-50). This also feeds **Q-30**.
* **Also answered or narrowed:**
  * **Q-01**: answered. The source is available: Java 17 and Spring Boot 3.2.2.
  * **Q-02**: narrowed. The engine is MySQL 8.0.35, database `lsdb`, `utf8mb4`. The schema
    is derivable from the JPA entities, but `mysqldump --no-data` is authoritative.
  * **Q-39**: narrowed. The code allows exactly the origins in `ALLOWED_ORIGINS`, with
    credentials. The template lists the apex, `www.` and `app.`; the production value is on
    the server. P-03 is still useful.

### 6.7 New open questions (proposed Q-45 to Q-53; add them to chapter 11)

Chapter 11 adopts these IDs. Q-48, Q-49 and Q-53 are listed in its §0 "Urgent", next to
Q-21…Q-23; the others are Gate 0 or Gate 1 questions. The proposals of 12c §7 are merged
into Q-21, Q-47, Q-50 and Q-53.

| ID | Question | Why it matters | Recommended default |
|---|---|---|---|
| Q-45 | What exactly runs on the production VPS? The proxy (nginx or Caddy), the deployed commit, any local changes, the key names in `.env` and `config/application-prod.yml`, the Ubuntu version, the VPS size and location. | The real starting point of the cut-over; parity of the configuration. | The owner runs the commands in §1.15 and shares names only. |
| Q-46 | Do any AWS-era resources still exist: Elastic Beanstalk environments (UAT and any production), RDS instances and snapshots, S3 buckets, IAM users and keys (including pair B)? Do they hold personal data? | Freeze completeness, data-protection exposure, cost, dangling DNS. | Inventory now. Snapshot and delete anything that is not needed (keep S3 names as redirects). |
| Q-47 | Where are `ordini.` and `dashboard.` hosted, and which `HOST` do they call? Is the CMS really at `dashboard.`? | Freeze, DNS plan, CORS. | Assume both call `https://app.logisticasanchez.com` until the owner says otherwise. |
| Q-48 | Is the backup cron installed? Is there any off-site copy? When was the last restore test? | Without a safe copy, no migration work should touch production. | Set up encrypted off-site backups and a test restore before Gate 1. |
| Q-49 | When does the `app.` certificate expire, and how is it renewed? | Possible outage before the cut-over (F-08). | Fix the renewal now. |
| Q-50 | Which host does `EMAIL_ORIGIN` use in production? | Old e-mail links (Q-29, Q-30). | Answer the `/mono/*` link paths on both the apex and `app.`. |
| Q-51 | What is `ls-web` for? Does it change the decision that the monolith serves the public pages? | Scope of the M1 public site. | Treat it as unrelated; the monolith serves the public pages as the brief says. |
| Q-52 | Where should the new app run: a new VPS (recommended), the legacy VPS shared, or managed AWS? Which database engine: PostgreSQL (the brief's default) or MySQL (if Path A is chosen)? | ADR 0001, cut-over and rollback. | A new VPS at the same provider. PostgreSQL under Path B; decide the engine in ADR 0001 under Path A. |
| Q-53 | Should the history be purged now (logs, keys, the Postman collection)? Does the personal data in the committed logs and in the production logs need a GDPR assessment? | F-02, F-10. | Purge now and record it in the register; production logs follow the retention set by Q-18. |
