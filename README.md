# jpa-bench

A fair, reproducible benchmark of Jakarta Persistence (JPA) providers, inspired by
[jpab.org](https://www.jpab.org). Every provider runs the **same** JMH benchmarks against the **same** schema
and the **same** generated data, with settings that are equivalent across providers and documented in
[CONFIG.md](CONFIG.md). Besides time, every benchmark reports **how many and which SQL statements** a provider
needs per operation, so you can see *why* a provider is faster or slower.

| Runner                 | Provider                       | Status        |
|------------------------|--------------------------------|---------------|
| `runner-openjpa-4.1`   | Apache OpenJPA 4.1.1           | stub          |
| `runner-openjpa-4.2`   | Apache OpenJPA 4.2.0-SNAPSHOT  | stub          |
| `runner-hibernate-6.6` | Hibernate ORM 6.6.59.Final     | stub          |
| `runner-hibernate-7`   | Hibernate ORM 7.4.15.Final     | done (reference runner) |
| `runner-eclipselink-4` | EclipseLink 4.0.10             | stub          |
| `runner-eclipselink-5` | EclipseLink 5.0.3              | stub          |

Databases: H2 2.x in-memory and PostgreSQL 18.1 (Testcontainers).

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
5. **Equivalent enhancement.** OpenJPA enhances the model at build time (it needs it). Hibernate runs without
   bytecode enhancement and EclipseLink without static weaving by default; both have an optional Maven profile
   to turn it on. The mode is recorded in `metadata.json` (`bench.provider.enhancement`).
6. **Results are really loaded.** Every benchmark touches the fields and collections the operation is
   supposed to load and folds them into a checksum consumed by a JMH `Blackhole`.
7. **Same JVM conditions.** One `EntityManagerFactory` per fork, forks with `-Xms2g -Xmx2g`, runs strictly
   one after another.

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

## Statement metrics

The pool is wrapped with [datasource-proxy](https://github.com/jdbc-observations/datasource-proxy); everything a
provider executes is counted, the harness' own JDBC work is not. Per operation the JMH JSON contains these
secondary metrics (JMH `@AuxCounters(EVENTS)`):

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
./mvnw -B package
```

Each implemented runner produces `<runner>/target/benchmarks.jar`.

## Run

```bash
./run.sh                                     # all runners, h2 + postgresql, dataset S, full JMH settings
./run.sh --runners runner-hibernate-7 --dbs h2 --datasets S,M
./run.sh --smoke --runners runner-hibernate-7   # 1 fork, 1+1 iterations of 1s: checks that everything runs
./run.sh --help
```

Results are written to `results/<timestamp>/`: one JMH JSON per `<runner>-<db>-<dataset>.json` (modes
Throughput and AverageTime), `<runner>-<db>-bootstrap.json` (emfBootstrap, single shot), `metadata.json`
(versions, JDK, OS, CPU, database image, seed, git commit), `sql/` and `logs/`.

A single runner jar can also be used directly with all JMH options:

```bash
java -jar runner-hibernate-7/target/benchmarks.jar -f 1 -wi 1 -i 1 -w 1s -r 1s -p db=h2 -p dataset=S \
     -rf json -rff smoke.json
```

### PostgreSQL

Each fork starts its own container through Testcontainers:

| Setting | Default | Environment variable |
|---|---|---|
| image | `postgres:18.1` | `JPABENCH_PG_IMAGE` |
| CPU limit | 2 CPUs (`NanoCPUs`) | `JPABENCH_PG_CPUS` |
| memory limit | 2048 MiB, no swap | `JPABENCH_PG_MEMORY_MB` |
| data directory | tmpfs (no disk I/O noise) | |

Set `JPABENCH_PG_URL` (and `JPABENCH_PG_USER`, `JPABENCH_PG_PASSWORD`) to use an existing server instead.
If Docker is not on the default socket set `DOCKER_HOST`, e.g.
`export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock` for Docker Desktop on macOS.

## Adding a provider

A runner is a Maven module that contains no Java code:

1. `pom.xml`: parent `jpa-bench`; dependencies `bench-core`, `bench-model`, exactly one provider (it brings its
   own `jakarta.persistence-api`), `h2` and `postgresql` (runtime); declare `maven-shade-plugin` without
   configuration (the parent configures `target/benchmarks.jar` with the JMH main class); enable resource
   filtering (`@property@` delimiters) for `bench-provider.properties`.
2. `src/main/resources/bench-provider.properties`: the provider's equivalent settings (see
   `runner-hibernate-7` and [CONFIG.md](CONFIG.md)):
   - `bench.provider.name`, `bench.provider.version` (`@...@` from the pom), `bench.provider.enhancement`,
     `bench.runner`
   - `bench.datasource.keys`: comma separated property keys under which the DataSource object is passed
   - `jakarta.persistence.provider`: the provider class
   - provider keys; `${bench.jdbc.batchSize}` references a common setting; `db.<h2|postgresql>.<key>` applies a
     key to one database only.
3. If the provider needs build-time enhancement, unpack `bench-model` into `target/classes`, enhance it there
   and exclude `bench-model` from the shaded jar (see the `enhance` profile of `runner-hibernate-7`).
4. Add a column to [CONFIG.md](CONFIG.md) and the runner to the status table above, then
   `./run.sh --smoke --runners <runner>`.

## Reproducing a run

`metadata.json` records the git commit, versions, JDK, OS, CPU, Docker and database image, the JMH settings and
the resolved settings of every runner; the data checksum is in every log (`# jpa-bench: dataset ...`). Check out
the commit, use the same JDK and run `./run.sh` with the same options.

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
