# Oracle cross-engine acceptance — 2026-10-10

Validated Oracle 19c 19.3 ORCLPDB against task-isolated MySQL 8.0.43 and PostgreSQL 18.3. All four Oracle-related directions passed direct JDBC and batch-adapter tests with 603 rows each. This validates the tested production SQL/type/data helpers; it does not claim that arbitrary metadata or every DataGrip end-to-end UI path is covered.

Results: 4886 cross-engine checks, 6 Oracle basic checks, 781 core checks, 2545 existing MySQL/PostgreSQL regression checks. See VALIDATION.md for exact scope and logs.

Fixes shipped in 0.3.3-alpha: boolean numeric digest, single-digit timezone offsets, valid Oracle timezone format, DEFAULT/NOT NULL order. Timestamp instants/offsets are preserved; region names are not preserved by offset-based transport. Oracle treats empty strings as NULL. Complex types/programs, arbitrary collation/index semantics and Oracle durable recovery/sync remain outside this acceptance.

Credentials are not stored. Oracle tests run in random task users and cleanup those users in finally; local endpoints verify server data directories before writes.

Run tools/run_oracle_cross.py with stdin properties url/user/password/container; do not put a password in a repository file. Both local task servers must already be started. This runner does not start or stop existing unrelated services.
