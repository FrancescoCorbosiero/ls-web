# Legacy import (one-off)

Owner decision of 2026-10-28 (Q-55 reopened, ADR 0048): the legacy customers and the orders of
the last six months move into the new app once, through a SQL file loaded with `psql`. There is
no importer in the application.

* `mysqldump.py` reads the tables of a `mysqldump` file (plain or `.gz`) without a MySQL server.
* `generate_import_sql.py` applies chapter 12b §5.5 and writes the SQL file; its report on
  stdout holds counts and legacy ids only.
* `test_generate_import_sql.py` checks the mapping on a synthetic dump:
  `python3 -I -m unittest discover -s tools/legacy-import -p 'test_*.py'`.

The dump and the generated file hold real personal data: keep them under `.local/legacy/`
(gitignored) or on the server, never in git, tests, docs, logs or a chat.

```sh
python3 -I tools/legacy-import/generate_import_sql.py \
    .local/legacy/lsdb_full_<data>.sql.gz .local/legacy/import_legacy.sql \
    [--months 6 | --cutoff 'YYYY-MM-DD HH:MM:SS'] [--exclude-emails .local/legacy/exclude.txt]
```

Load it once, after the migrations and after creating the first ADMIN (`create-staff-user`):

```sh
psql -v ON_ERROR_STOP=1 -d ls_web -f .local/legacy/import_legacy.sql
```

The file runs in one transaction, takes every id from the sequences, refuses a second load
(`audit_log` action `legacy.import`) and stops on any conflict with the data already present.
