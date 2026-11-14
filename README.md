# jpa-bench

A reproducible JMH benchmark of Jakarta Persistence (JPA) providers, inspired by [jpab.org](https://www.jpab.org).
Every provider runs the same benchmarks on the same schema and data with equivalent settings
([CONFIG.md](CONFIG.md)). Every benchmark also reports which and how many SQL statements a provider sends per
operation.

Results: **https://rzo1.github.io/jpa-bench/** (see [Results and contributing a run](#results-and-contributing-a-run)).

| Runner                       | Provider                                   | Enhancement (default build)            |
|------------------------------|--------------------------------------------|----------------------------------------|
| `runner-openjpa-4.1`         | Apache OpenJPA 4.1.1                       | build time (required)                  |
| `runner-openjpa-4.2`         | Apache OpenJPA 4.2.0-SNAPSHOT (latest Apache snapshot; build and revision recorded) | build time (required) |
| `runner-openjpa-4.1-agent`   | Apache OpenJPA 4.1.1                       | runtime: `-javaagent` at class load    |
| `runner-openjpa-4.2-agent`   | Apache OpenJPA 4.2.0-SNAPSHOT              | runtime: `-javaagent` at class load    |
| `runner-hibernate-6.6`       | Hibernate ORM 6.6.59.Final                 | none (`-Penhance`: build time)          |
| `runner-hibernate-7`         | Hibernate ORM 7.4.15.Final                 | none (`-Penhance`: build time)          |
| `runner-eclipselink-4`       | EclipseLink 4.0.10                         | none, not woven (`-Pweave`: static)     |
| `runner-eclipselink-5`       | EclipseLink 5.0.2                          | none, not woven (`-Pweave`: static)     |
| `runner-eclipselink-4-woven` | EclipseLink 4.0.10                         | static weaving at build time           |
| `runner-eclipselink-5-woven` | EclipseLink 5.0.2                          | static weaving at build time           |

All runners run by default. Databases: H2 2.x in-memory and PostgreSQL 18.1 (Testcontainers).

## Layout

```
bench-model/      entities, jakarta.persistence annotations only (JPA 3.1 API, provided scope)
bench-data/       deterministic generator, DDL scripts (src/main/resources/ddl/<db>.sql), JDBC loader
bench-core/       JMH benchmarks (JPA API only), BenchEnvironment, statement metrics, persistence.xml
runner-*/         one provider each: bench-core + bench-model + provider + JDBC drivers -> target/benchmarks.jar
bench-viz/        Python: reports, results site, run validation
results-branch/   template of the orphan branch "results" (published runs, Pages workflow)
run.sh            runs the matrix runner x database x dataset
```

## Fairness rules

1. **One schema, from our DDL only.** `bench-data/src/main/resources/ddl/h2.sql` and `postgresql.sql` are
   executed with plain JDBC before data is loaded. No provider may generate, update or validate the schema
   (`jakarta.persistence.schema-generation.database.action=none` for all; Hibernate `hbm2ddl.auto=none`,
   OpenJPA without `SynchronizeMappings`, EclipseLink `ddl-generation=none`). Helper tables a provider would
   create on its own (e.g. Hibernate's global temporary tables for multi-table bulk mutations) are switched off.
2. **Identical data.** `DataGenerator` draws every value from one `SplittableRandom` with the fixed seed
   `20260101`, ids are assigned by the application (no IDENTITY, no sequences). The loader logs a checksum of
   all generated records; it must be the same for every provider. Read benchmarks load the data once per trial;
   write benchmarks reset the affected tables with JDBC before every iteration.
3. **Same pool, same transactions.** One HikariCP pool (fixed size 4, auto-commit off, `READ_COMMITTED`) is
   handed to the provider as a `DataSource` object (`jakarta.persistence.nonJtaDataSource` or the provider's
   equivalent key). Every operation runs in a fresh `EntityManager` and a resource-local transaction, reads
   included.
4. **Equivalent provider settings.** Same JDBC batch size (50), ordered inserts/updates where the provider has
   such an option, second level cache and query cache off, no provider-side statement cache, no SQL logging or
   statistics. The full table is in [CONFIG.md](CONFIG.md); each runner's settings are in its
   `src/main/resources/bench-provider.properties`.
5. **Enhancement per provider default.** OpenJPA is always enhanced (lazy to-one, field-level dirty tracking),
   at build time (`runner-openjpa-N`) or by its javaagent at class load (`runner-openjpa-N-agent`: run.sh starts
   every fork with `-javaagent:<runner>/target/javaagent.jar`; same enhanced classes, the difference shows in
   bootstrap and cold start). Hibernate runs without bytecode enhancement (proxies, snapshot dirty checking),
   EclipseLink without weaving (`-Penhance` / `-Pweave` switch it on). This is not equivalent work:
   - **EclipseLink without weaving** loads every `@ManyToOne(fetch = LAZY)` eagerly and `getReference()` runs a
     SELECT, so joins, pages, `persistBatch` and `resultStream` load extra rows. Unwoven and woven EclipseLink
     are therefore separate runners (`runner-eclipselink-N`, `runner-eclipselink-N-woven`); compare the woven
     one with the others.
   - Running an `-agent` jar by hand needs `-jvmArgsAppend -javaagent:<runner>/target/javaagent.jar`, otherwise
     it fails (`openjpa.RuntimeUnenhancedClasses=unsupported`).

   Every bench-viz label names the enhancement, e.g. `EclipseLink 5.0.2 (not woven)`; `metadata.json` records
   it (`bench.provider.enhancement`).
6. **Same results.** Every benchmark touches what the operation loads and folds it into a checksum for a JMH
   `Blackhole`. Before measuring, every read trial logs a checksum per read operation for fixed inputs
   (`# jpa-bench: verify <operation>=<checksum>`, `Verification.java`); run.sh collects them in
   `verification.tsv` and writes `verification-mismatches.txt` when providers differ. Writes are not verified.
7. **Same JVM conditions.** One `EntityManagerFactory` per fork, `-Xms2g -Xmx2g` (`run.sh --jvm-args` replaces
   it for all runners), one benchmark thread (`-t` > 1 fails: the statement counters are per JVM), jobs one
   after another. `run.sh --order interleave|shuffle` spreads thermal drift; `execution.tsv` has the actual order.
8. **Metering is not timed.** Benchmarks are timed on the plain pool; statement metrics come from a separate
   short metered run (`--metering separate`, default; `inline`: one metered run, `off`: no metrics).

## Benchmarks

| Benchmark | What it does |
|---|---|
| `findById` | `em.find(Customer)` by random id |
| `jpqlByIndexedAttribute` | JPQL by unique, indexed e-mail |
| `lazyNavigationNPlusOne` | 20 orders, then navigate lazy `customer` and `lines` (N+1 selects) |
| `joinFetch` | the same data with `join fetch o.customer join fetch o.lines` |
| `manyToManyAndElementCollection` | 10 products, navigate `categories` (ManyToMany) and `tags` (ElementCollection) |
| `inheritanceSingleTableQuery` | polymorphic query over 50 persons, SINGLE_TABLE |
| `inheritanceJoinedQuery` | polymorphic query over 50 persons, JOINED |
| `criteriaQuery` | Criteria API: customers of a city since a date, ordered, max 50 |
| `pagination` | count query + one page of 20 orders ordered by date |
| `dtoProjection` | JPQL constructor expression into a record, max 50 |
| `aggregationGroupBy` | `count`/`sum` grouped by city over 30 days of orders |
| `resultStream` | `getResultStream()` over 2000 orders |
| `persistBatch` | persist 50 orders with 3 lines each (`getReference` for customer/product) |
| `updateDirtyChecking` | load 50 orders, change 2 attributes, commit (versioned updates) |
| `removeBatch` | find 50 orders and `remove` them (lines by cascade) |
| `bulkUpdate` | one JPQL `update` over 500 orders |
| `bulkDelete` | JPQL `delete` of 50 orders and their lines |
| `emfBootstrap` | create EMF, read metamodel, one `find`, close (single shot) |

Parameters: `db` (`h2`, `postgresql`), `dataset` (`S`, `M`, `L`: 1k/10k/50k customers, 5 orders per customer,
1-5 lines per order, 500/5k/25k products, 1k/10k/50k persons per hierarchy) and `entities` (rows per write
operation, default 50).

### Modes

- **Read benchmarks**: Throughput and AverageTime, time-bound iterations; they never modify data.
- **Write benchmarks**: SingleShotTime with a fixed batch of 200 operations per iteration
  (`@Measurement(batchSize = 200)`), tables reset before every iteration, so every provider writes the same rows
  from the same table sizes (in a time-bound mode a faster provider would write more rows and then work against
  bigger tables and more garbage). JMH reports the time of the batch (unit `ms/op` regardless); bench-viz divides by
  `measurementBatchSize`. Per-invocation setup (rows that `removeBatch`/`bulkDelete` delete) is not timed.
- **`emfBootstrap`**: SingleShotTime, warm (`<runner>-<db>-bootstrap.json`: 5 warmup + 20 measured shots per
  fork) and cold start (`<runner>-<db>-coldstart.json`: first shot of 10 fresh JVMs, class loading included). For `runner-openjpa-N-agent` the
  agent's `premain` runs before the benchmark, so its startup cost is not in these numbers; enhancement of the
  model classes on first load is.

## Statement metrics

[datasource-proxy](https://github.com/jdbc-observations/datasource-proxy) wraps the pool and counts everything
a provider executes, not the harness' own JDBC work. Secondary metrics per operation (`@AuxCounters(EVENTS)`),
with `--metering separate` in `metrics/` (the timed runs report 0):

| Metric | Meaning |
|---|---|
| `stmtSelect`, `stmtInsert`, `stmtUpdate`, `stmtDelete`, `stmtOther`, `stmtTotal` | logical statements by kind (a batch with n parameter sets counts n) |
| `jdbcExecutions` | round trips (`execute*` calls, one per `executeBatch`) |
| `jdbcBatches`, `jdbcBatchedRows` | `executeBatch` calls and the parameter sets they carried |
| `sqlChars` | total SQL text length sent |
| `selectJoins` | joined tables over all SELECTs: `JOIN` keywords plus the extra tables of comma-separated FROM lists (theta joins, as EclipseLink writes them), subqueries included |
| `selectMaxChars` | longest SELECT of the iteration (not per op) |
| `ops` | operations of the iteration (raw count) |

JMH sums `EVENTS` counters over iterations and forks: the per-operation value is the mean of `rawData`, not
`score`. The distinct SQL of the first iteration per benchmark is in `sql/<runner>-<db>-<dataset>/`.

## Build

Requires JDK 21+ and, for PostgreSQL, Docker.

```bash
./mvnw -B package                       # all modules, tests included
./mvnw -B package -Penhance,weave       # Hibernate enhanced, EclipseLink runners woven
```

Each runner produces `<runner>/target/benchmarks.jar`. Every build first deletes model classes of an earlier
`-Penhance`/`-Pweave` build (`remove-stale-model` in the root pom). run.sh builds with `clean package` and
passes `--profiles` to Maven.

## Run

```bash
./run.sh                                          # all runners, h2 + postgresql, dataset S, full JMH settings
./run.sh --runners runner-openjpa-4.2,runner-eclipselink-5-woven --dbs h2 --datasets S,M
./run.sh --smoke --dbs h2                         # 1 fork, 1+1 iterations of 1s: checks that everything runs
./run.sh --order shuffle --seed 42                # random job order (recorded), against drift
./run.sh --profiles enhance --runners runner-hibernate-7   # Hibernate with bytecode enhancement
./run.sh --contributor <github-handle> --machine "MacBook Pro M4 Max"   # recorded in metadata.json
./run.sh --help
```

A failing job is logged and the matrix continues; run.sh exits with 1 at the end.

Results are written to `results/<timestamp>/`:

| File | Content |
|---|---|
| `<runner>-<db>-<dataset>.json` | JMH JSON (reads: Throughput and AverageTime; writes: single shot with a fixed batch) |
| `metrics/<runner>-<db>-<dataset>.json` | short metered run with the statement metrics (`--metering separate`) |
| `<runner>-<db>-bootstrap.json`, `<runner>-<db>-coldstart.json` | emfBootstrap warm and cold start |
| `metadata.json` | git commit, build profiles, JDK, OS, machine (model, chip, cores, memory, power source, Low Power Mode; DMI product and governor on Linux), contributor, smoke flag, Docker, PostgreSQL image (by digest), JMH, metering and order settings, resolved settings of every runner per database |
| `execution.tsv` | the jobs in the order they ran, with start/end time and status |
| `verification.tsv` | result checksums of the read operations per runner (fairness rule 6) |
| `datasets.tsv` | loaded dataset per runner and database: seed, checksum, row counts |
| `sql/`, `logs/` | distinct SQL per benchmark, complete JMH output |

A single runner jar can also be used directly with all JMH options:

```bash
java -jar runner-hibernate-7/target/benchmarks.jar -f 1 -wi 1 -i 1 -w 1s -r 1s -p db=h2 -p dataset=S \
     -rf json -rff smoke.json
```

### PostgreSQL

Each fork starts its own container through Testcontainers:

| Setting | Default | Environment variable |
|---|---|---|
| image | `postgres:18.1`, pinned by digest (`postgres.image` in the root pom, the only place it is defined) | `JPABENCH_PG_IMAGE` |
| CPU limit | 2 CPUs (`NanoCPUs`) | `JPABENCH_PG_CPUS` |
| memory limit | 2048 MiB, no swap | `JPABENCH_PG_MEMORY_MB` |
| data directory | tmpfs (no disk I/O noise) | |
| server settings | `fsync=off` (Testcontainers default), `autovacuum=off` | |

The loader runs `ANALYZE` after the bulk load (H2 too); every write iteration starts with
`VACUUM ANALYZE ORDERS, ORDER_LINE`. With an external server (`JPABENCH_PG_URL`) switch autovacuum off yourself.
Every trial logs the server version and settings (`shared_buffers`, `work_mem`, `fsync`, `jit`, ...) in its
`# jpa-bench: provider ...` line.

Set `JPABENCH_PG_URL` (and `JPABENCH_PG_USER`, `JPABENCH_PG_PASSWORD`) to use an existing server instead.
If Docker is not on the default socket set `DOCKER_HOST`, e.g.
`export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` for Docker Desktop on macOS.

## Adding a provider

A runner is a Maven module that contains no Java code:

1. `pom.xml`: parent `jpa-bench`; dependencies `bench-core`, `bench-model`, exactly one provider (it brings its
   own `jakarta.persistence-api`), `h2` and `postgresql` (runtime); declare `maven-shade-plugin` without
   configuration (the parent configures `target/benchmarks.jar` with the JMH main class); enable resource
   filtering (`@property@` delimiters) for `bench-provider.properties`. Any existing runner is a template:
   `runner-hibernate-7` (no enhancement, optional profile), `runner-openjpa-4.1` (enhancement required),
   `runner-eclipselink-5` and `runner-eclipselink-5-woven` (two runners sharing one settings file),
   `runner-openjpa-4.1-agent` (runtime enhancement with the provider's javaagent).
2. `src/main/resources/bench-provider.properties`: the provider's equivalent settings (see
   [CONFIG.md](CONFIG.md)):
   - `bench.provider.name`, `bench.provider.version` (`@...@` from the pom), `bench.provider.enhancement`
     (`none` or a description; bench-viz shows it in every label), `bench.runner=@project.artifactId@`
   - `bench.datasource.keys`: comma separated property keys under which the DataSource object is passed
   - `jakarta.persistence.provider`: the provider class
   - provider keys; `${bench.jdbc.batchSize}` references a common setting; `db.<h2|postgresql>.<key>` applies a
     key to one database only.
3. If the provider needs build-time enhancement, unpack `bench-model` into `target/classes`, enhance it there
   and exclude `bench-model` from the shaded jar (property `bench.shade.excludeModel`, see the `enhance`
   profile of `runner-hibernate-7` or the OpenJPA runners).
4. Add the runner to `<modules>` of the root pom, to `ALL_RUNNERS` in run.sh, to `KNOWN_RUNNERS` in
   `bench-viz/src/jpa_bench_viz/results.py` (fixed colour), to the CI smoke run, a column to
   [CONFIG.md](CONFIG.md) and a row to the table above, then `./run.sh --smoke --dbs h2 --runners <runner>` and
   check that `verification.tsv` shows no mismatch.

## Reproducing a run

Check out the commit from `metadata.json`, use the same JDK and run `./run.sh` with the same options;
`execution.tsv` has the job order. The data checksum is logged (`# jpa-bench: dataset ...`) and collected in
`datasets.tsv`; for dataset S and the default seed it is `17f556d4e3d90c3d` (`DatasetLoaderTest`).

## Visualisation

`bench-viz/` (Python 3.12+, [uv](https://docs.astral.sh/uv/)) writes one self-contained HTML report per results
directory: run summary, scores normalized to the fastest provider, statements per operation, SQL per benchmark,
provider settings, verification. Details in [bench-viz/README.md](bench-viz/README.md).

```bash
cd bench-viz
uv run jpa-bench-viz ../results/<timestamp>              # ../results/<timestamp>/report/index.html
```

## Results and contributing a run

Published runs live on the orphan branch `results` under `runs/<yyyy-mm-dd>-<machine>-<contributor>/`. Every
push to it builds https://rzo1.github.io/jpa-bench/: an index of all runs, a page per run (machine, OS, JDK,
database image, commit, provider versions and enhancement, OpenJPA snapshot build and revision, JMH settings,
verification) and the data model and dataset sizes. Smoke and reduced runs are listed separately as not
comparable. Compare providers within a run, not runs from different machines.

To contribute a run:

1. On a clean checkout of `main`, on AC power, Low Power Mode off, nothing else running:
   `./run.sh --contributor <github-handle> --machine "<short label>"` (all runners, H2 and PostgreSQL; hours).
2. Fork the repository, check out `results` and add the run:
   ```bash
   cd bench-viz
   uv run jpa-bench-viz add-run ../results/<timestamp> <results-checkout>/runs
   uv run jpa-bench-viz validate --submission <results-checkout>/runs/<run-id>
   ```
   `add-run` gzips the logs and leaves out `report/`.
3. Open a pull request against `results` that adds only `runs/<run-id>/`. CI validates it and builds the site.

Published: everything `run.sh` writes except `report/`, logs gzipped. Validation rejects runs with failed or
missing jobs, verification mismatches, dataset checksums other than the reference, or no contributor.

The branch is created once from [results-branch/](results-branch/) with `results-branch/bootstrap.sh` (an
orphan branch in a new worktree; push it, then set Settings > Pages > Source to GitHub Actions).

## License

Apache License 2.0, see [LICENSE](LICENSE).
