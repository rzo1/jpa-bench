# Equivalent provider settings

Every run combines `bench-core/src/main/resources/bench-common.properties` (identical for all runners) with the
runner's `src/main/resources/bench-provider.properties`. `bench.*` keys configure the harness and are never
passed to the provider; `db.<id>.<key>` applies to one database only; `${key}` references another setting.
`java -cp <runner>/target/benchmarks.jar io.github.rzo1.jpabench.core.ProviderInfo <db>` prints the resolved
settings; run.sh stores them in `metadata.json`.

## Common settings (all runners)

| Setting | Value | Why |
|---|---|---|
| `jakarta.persistence.sharedCache.mode` | `NONE` | no second level cache |
| `jakarta.persistence.validation.mode` | `NONE` | no Bean Validation on lifecycle events |
| `jakarta.persistence.schema-generation.database.action` | `none` | schema only from our DDL |
| `jakarta.persistence.schema-generation.scripts.action` | `none` | |
| `jakarta.persistence.transactionType` | `RESOURCE_LOCAL` | |
| `bench.pool.size` | `4` | HikariCP, minimumIdle = maximumPoolSize |
| `bench.pool.autoCommit` | `false` | |
| `bench.pool.isolation` | `TRANSACTION_READ_COMMITTED` | set by the pool, providers do not touch it |
| `bench.jdbc.batchSize` | `50` | referenced by every provider's batch size setting |
| `bench.datasource.keys` | `jakarta.persistence.nonJtaDataSource` | key(s) for the DataSource object; runners may override |

## Provider equivalents

| Concern | Hibernate ORM 7 | Hibernate ORM 6.6 | OpenJPA 4.1 / 4.2 | EclipseLink 4 / 5 |
|---|---|---|---|---|
| provider class | `org.hibernate.jpa.HibernatePersistenceProvider` | TODO | TODO | TODO |
| DataSource object | `jakarta.persistence.nonJtaDataSource` | TODO | TODO | TODO |
| no schema generation / validation | `hibernate.hbm2ddl.auto=none` | TODO | TODO (no `openjpa.jdbc.SynchronizeMappings`) | TODO (`eclipselink.ddl-generation=none`) |
| no provider helper tables | `hibernate.query.mutation_strategy.global_temporary.create_tables=false`, `...drop_tables=false` | TODO | TODO | TODO |
| JDBC batch size 50 | `hibernate.jdbc.batch_size=${bench.jdbc.batchSize}` | TODO | TODO | TODO |
| ordered inserts / updates | `hibernate.order_inserts=true`, `hibernate.order_updates=true` | TODO | TODO | TODO |
| batch versioned updates | `hibernate.jdbc.batch_versioned_data=true` | TODO | TODO | TODO |
| no L2 cache | `hibernate.cache.use_second_level_cache=false` | TODO | TODO | TODO |
| no query cache | `hibernate.cache.use_query_cache=false` | TODO | TODO | TODO |
| statement cache | none in Hibernate (driver defaults) | TODO | TODO | TODO |
| auto-commit already off | `hibernate.connection.provider_disables_autocommit=true` | TODO | n/a | n/a |
| no statistics / SQL logging | `hibernate.generate_statistics=false`, `show_sql=false`, `format_sql=false`, `use_sql_comments=false` | TODO | TODO | TODO |
| bytecode enhancement | none by default; `-Penhance`: build time, lazy initialization + dirty tracking | TODO | build time (required) | TODO (static weaving off by default) |
