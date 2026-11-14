# jpa-bench

A fair, reproducible benchmark of Jakarta Persistence (JPA) providers, inspired by
[jpab.org](https://www.jpab.org). Every provider runs the **same** JMH benchmarks against the **same** schema
and the **same** generated data, with settings that are equivalent across providers and documented in
[CONFIG.md](CONFIG.md). Besides time, every benchmark reports **how many and which SQL statements** a provider
needs per operation, so you can see *why* a provider is faster or slower.

| Runner                       | Provider                                   | Enhancement (default build)            |
|------------------------------|--------------------------------------------|----------------------------------------|
| `runner-openjpa-4.1`         | Apache OpenJPA 4.1.1                       | build time (required)                  |
| `runner-openjpa-4.2`         | Apache OpenJPA 4.2.0-SNAPSHOT (latest build of the Apache snapshot repository; the resolved build is recorded) | build time (required) |
| `runner-hibernate-6.6`       | Hibernate ORM 6.6.59.Final                 | none (`-Penhance`: build time)          |
| `runner-hibernate-7`         | Hibernate ORM 7.4.15.Final                 | none (`-Penhance`: build time)          |
| `runner-eclipselink-4`       | EclipseLink 4.0.10                         | none, not woven (`-Pweave`: static)     |
| `runner-eclipselink-5`       | EclipseLink 5.0.2 (5.0.3 is not released yet, Maven Central only has 5.0.3-RC1) | none, not woven (`-Pweave`: static) |
| `runner-eclipselink-4-woven` | EclipseLink 4.0.10                         | static weaving at build time           |
| `runner-eclipselink-5-woven` | EclipseLink 5.0.2                          | static weaving at build time           |

All runners are implemented and run by default. Databases: H2 2.x in-memory and PostgreSQL 18.1 (Testcontainers).

## Layout

```
bench-model/      entities, jakarta.persistence annotations only (JPA 3.1 API, provided scope)
bench-data/       deterministic generator, DDL scripts (src/main/resources/ddl/<db>.sql), JDBC loader
bench-core/       JMH benchmarks (JPA API only), BenchEnvironment, statement metrics, persistence.xml
runner-*/         one provider each: bench-core + bench-model + provider + JDBC drivers -> target/benchmarks.jar
bench-viz/        Python tool that turns results into charts and an HTML report
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
5. **Enhancement per provider default.** Every provider runs the way it runs out of the box: OpenJPA enhances
   the model at build time (it requires it), Hibernate runs without bytecode enhancement, EclipseLink without
   weaving (`-Penhance` / `-Pweave` turn it on). This is *not* equivalent work, and the consequences are visible
   in the results:
   - **EclipseLink without weaving** cannot load a to-one association lazily: every `@ManyToOne(fetch = LAZY)`
     is loaded eagerly, and `getReference()` runs a SELECT. Joins and pages load the customer of every order,
     `persistBatch` selects every referenced customer and product, `resultStream` loads one customer per
     order. These numbers measure EclipseLink *without weaving*, not EclipseLink.
   - **Hibernate without enhancement** uses proxies for lazy to-one associations and snapshot dirty checking,
     so it loads only what is touched.
   - **OpenJPA** is always enhanced (lazy to-one fields, field-level dirty tracking).

   Therefore unwoven and woven EclipseLink are both in the default matrix, as separate runners
   (`runner-eclipselink-N` and `runner-eclipselink-N-woven`, identical settings except for the weaving), and
   every bench-viz label names the enhancement, e.g. `EclipseLink 5.0.2 (not woven)`. Compare woven
   EclipseLink with the others; read unwoven EclipseLink as its own category. The mode is recorded in
   `metadata.json` (`bench.provider.enhancement`).
6. **Results are really loaded, and they are the same.** Every benchmark touches the fields and collections the
   operation is supposed to load and folds them into a checksum consumed by a JMH `Blackhole`. Before measuring,
   every read trial runs each read operation for fixed inputs and logs its checksum
   (`# jpa-bench: verify <operation>=<checksum>`, see `Verification.java`). run.sh collects them into
   `verification.tsv` and warns (and writes `verification-mismatches.txt`) when providers return different
   results; the bench-viz report flags them. Write benchmarks are not verified this way.
7. **Same JVM conditions.** One `EntityManagerFactory` per fork, forks with `-Xms2g -Xmx2g`
   (`@Fork(jvmArgs)`; `run.sh --jvm-args "..."` replaces them for every runner alike), one benchmark thread
   (the statement counters are per JVM; a trial with `-t` > 1 fails), runs strictly one after another.
   `run.sh --order interleave|shuffle` keeps thermal drift and background load from favouring the runners
   that happen to run first; the actual order and timestamps are written to `execution.tsv`.
8. **Metering is not timed.** Counting statements costs time on every statement (proxy, SQL classification), so
   chatty providers would pay more than their real cost. By default run.sh times every benchmark with the plain
   pool (`JPABENCH_METERING=off`) and collects the statement metrics in a separate, short metered run
   (`--metering separate`; `inline` reproduces the old single run, `off` skips the metrics).

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

- **Read benchmarks** (`ReadBenchmarks`): Throughput and AverageTime with time-bound iterations. They never
  modify data, so a faster provider doing more operations per iteration changes nothing for the next one.
- **Write benchmarks** (`WriteBenchmarks`): SingleShotTime with a **fixed batch of 200 operations per
  iteration** (`@Warmup/@Measurement(batchSize = 200)`), tables reset before every iteration. Every provider
  therefore writes exactly the same rows per iteration and starts each iteration from the same table sizes;
  in a time-bound mode a faster provider would write more rows and work against bigger tables, more garbage
  (H2 lives in the benchmark heap) and more dead rows (PostgreSQL). JMH reports the time of the whole batch
  of 200 operations (its unit nevertheless says `ms/op`); bench-viz divides it by the `measurementBatchSize`
  of the JMH JSON and shows milliseconds per operation. Per-invocation setup (the JDBC insert of the rows
  `removeBatch`/`bulkDelete` delete) is not timed.
- **`emfBootstrap`**: SingleShotTime, run twice: **warm** (`<runner>-<db>-bootstrap.json`, 5 warmup shots, then
  20 measured shots per fork: the cost of another EMF in a running JVM) and **cold start**
  (`<runner>-<db>-coldstart.json`, `-wi 0 -i 1` with 10 forks: the first EMF of a fresh JVM, class loading
  included).

## Statement metrics

The pool is wrapped with [datasource-proxy](https://github.com/jdbc-observations/datasource-proxy); everything a
provider executes is counted, the harness' own JDBC work is not. Per operation the JMH JSON contains these
secondary metrics (JMH `@AuxCounters(EVENTS)`). With `--metering separate` (the default) they come from the
metered run in `results/<timestamp>/metrics/`; the counters of the timed (unmetered) runs are all 0.

| Metric | Meaning |
|---|---|
| `stmtSelect`, `stmtInsert`, `stmtUpdate`, `stmtDelete`, `stmtOther`, `stmtTotal` | logical statements by kind (a batch with n parameter sets counts n) |
| `jdbcExecutions` | round trips (`execute*` calls, one per `executeBatch`) |
| `jdbcBatches`, `jdbcBatchedRows` | `executeBatch` calls and the parameter sets they carried |
| `sqlChars` | total SQL text length sent |
| `selectJoins` | joined tables over all SELECTs: `JOIN` keywords plus the extra tables of comma-separated FROM lists (theta joins, as EclipseLink writes them), subqueries included |
| `selectMaxChars` | longest SELECT of the iteration (not per op) |
| `ops` | operations of the iteration (raw count) |

JMH sums `EVENTS` counters over iterations and forks, so the per-operation value of a metric is the **mean of
its `rawData`** (bench-viz does this), not its `score`. The distinct SQL texts of the first iteration of every
benchmark are written to `results/<timestamp>/sql/<runner>-<db>-<dataset>/` to compare the generated SQL.

## Build

Requires JDK 21+ and, for PostgreSQL, Docker.

```bash
./mvnw -B package                       # all modules, tests included
./mvnw -B package -Penhance,weave       # Hibernate enhanced, EclipseLink runners woven
```

Each runner produces `<runner>/target/benchmarks.jar`. A build always starts by deleting model classes left in a
runner's `target/classes` by an earlier `-Penhance`/`-Pweave` build (execution `remove-stale-model` of the root
pom), so a jar never contains enhanced or woven classes its recorded `bench.provider.enhancement` does not
mention. run.sh always builds with `clean package` and passes `--profiles` on to Maven.

## Run

```bash
./run.sh                                          # all runners, h2 + postgresql, dataset S, full JMH settings
./run.sh --runners runner-openjpa-4.2,runner-eclipselink-5-woven --dbs h2 --datasets S,M
./run.sh --smoke --dbs h2                         # 1 fork, 1+1 iterations of 1s: checks that everything runs
./run.sh --order shuffle --seed 42                # random job order (recorded), against drift
./run.sh --profiles enhance --runners runner-hibernate-7   # Hibernate with bytecode enhancement
./run.sh --help
```

Options in short: `--runners/--dbs/--datasets/--include` select the matrix, `--forks/--warmup-iterations/
--iterations/--warmup-time/--time` the JMH settings (iteration times apply to the read benchmarks only),
`--bootstrap-iterations/--cold-forks/--no-bootstrap` the bootstrap runs, `--metering separate|inline|off`,
`--order sequential|interleave|shuffle` and `--seed`, `--profiles`, `--skip-build`, `--jvm-args`, `--out`.
A failing job is logged and the matrix continues; run.sh exits with 1 at the end.

Results are written to `results/<timestamp>/`:

| File | Content |
|---|---|
| `<runner>-<db>-<dataset>.json` | JMH JSON (reads: Throughput and AverageTime; writes: single shot with a fixed batch) |
| `metrics/<runner>-<db>-<dataset>.json` | short metered run with the statement metrics (`--metering separate`) |
| `<runner>-<db>-bootstrap.json`, `<runner>-<db>-coldstart.json` | emfBootstrap warm and cold start |
| `metadata.json` | git commit, build profiles, JDK, OS, CPU, Docker, PostgreSQL image (by digest) and settings, JMH, metering and order settings, the resolved settings of every runner per database |
| `execution.tsv` | the jobs in the order they ran, with start/end time and status |
| `verification.tsv` | result checksums of the read operations per runner (see fairness rule 6) |
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

Statistics are not left to chance: the loader runs `ANALYZE` right after the bulk load (on H2 too), and before
every write iteration the harness runs `VACUUM ANALYZE ORDERS, ORDER_LINE`, so each iteration starts from the
same physical state and no background autovacuum runs while measuring. With an external server
(`JPABENCH_PG_URL`) switch autovacuum off yourself for comparable runs.

Every trial logs the server version (`SELECT version()`) and the relevant settings (`shared_buffers`,
`work_mem`, `synchronous_commit`, `fsync`, `jit`, `autovacuum`, ...) in its `# jpa-bench: provider ...` line.

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
   `runner-eclipselink-5` and `runner-eclipselink-5-woven` (two runners sharing one settings file).
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

`metadata.json` records the git commit, the build profiles, versions, JDK, OS, CPU, Docker, the PostgreSQL image
by digest, the JMH, metering and order settings (with the shuffle seed) and the resolved settings of every
runner per database; `execution.tsv` the actual job order. The data checksum is in every log
(`# jpa-bench: dataset ...`); for dataset S and the default seed it must be `17f556d4e3d90c3d` (asserted by
`DatasetLoaderTest`). Check out the commit, use the same JDK and run `./run.sh` with the same options.

## Visualisation

`bench-viz/` (Python 3.12+, managed with [uv](https://docs.astral.sh/uv/), pandas + matplotlib) reads a results
directory and writes comparison charts and one self-contained static HTML report:

```bash
cd bench-viz
uv run jpa-bench-viz ../results/<timestamp>              # writes ../results/<timestamp>/report/index.html
uv run jpa-bench-viz ../results/<timestamp> --format png --out /tmp/report
```

Per database and dataset the report contains:

- every benchmark normalized to the fastest provider (1.0 = fastest; throughput `score / best`, time modes
  `best / score`) with error bars, per JMH mode, and tables with the absolute scores;
- statements per operation stacked by kind (SELECT, INSERT, UPDATE, DELETE, other), JDBC round trips, SQL
  characters, the longest SELECT and joined tables per operation, to see which provider sends more or bigger SQL;
- per benchmark the absolute scores and the distinct SQL each provider sent in the first iteration;
- the run's environment from `metadata.json` and every provider's settings.

It also writes `scores.csv` and `statements.csv` for further analysis. See [bench-viz/README.md](bench-viz/README.md)
for how the numbers are read and for development (`uv run ruff check`, `uv run ruff format --check`,
`uv run pytest`).

## License

Apache License 2.0, see [LICENSE](LICENSE).
