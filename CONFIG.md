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
| provider class | `org.hibernate.jpa.HibernatePersistenceProvider` | `org.hibernate.jpa.HibernatePersistenceProvider` | `org.apache.openjpa.persistence.PersistenceProviderImpl` | `org.eclipse.persistence.jpa.PersistenceProvider` |
| DataSource object | `jakarta.persistence.nonJtaDataSource` | `jakarta.persistence.nonJtaDataSource` | `openjpa.ConnectionFactory` (`nonJtaDataSource` is a JNDI name for OpenJPA; `ConnectionFactory2` unset, falls back to the same pool) | `jakarta.persistence.nonJtaDataSource` (used as an external pool) |
| no schema generation / validation | `hibernate.hbm2ddl.auto=none` | `hibernate.hbm2ddl.auto=none` | no `openjpa.jdbc.SynchronizeMappings`; default `dynamic` schema factory (does not read the schema) | `eclipselink.ddl-generation=none` |
| no provider helper tables | `hibernate.query.mutation_strategy.global_temporary.create_tables=false`, `...drop_tables=false` | same keys as 7 (`hibernate.query.mutation_strategy.global_temporary.create_tables=false`, `...drop_tables=false`) | none needed (application-assigned ids, no sequence table) | none needed (application-assigned ids, no sequence table) |
| JDBC batch size 50 | `hibernate.jdbc.batch_size=${bench.jdbc.batchSize}` | `hibernate.jdbc.batch_size=${bench.jdbc.batchSize}` | `openjpa.jdbc.DBDictionary=batchLimit=${bench.jdbc.batchSize}` (dictionary auto-detected) | `eclipselink.jdbc.batch-writing=JDBC`, `eclipselink.jdbc.batch-writing.size=${bench.jdbc.batchSize}` |
| ordered inserts / updates | `hibernate.order_inserts=true`, `hibernate.order_updates=true` | `hibernate.order_inserts=true`, `hibernate.order_updates=true` | default `batching-constraint` update manager orders by FK; `openjpa.jdbc.MappingDefaults=ForeignKeyDeleteAction=restrict, JoinForeignKeyDeleteAction=restrict` declares the DDL's FKs logically (without it inserts/deletes are not batched across orders) | `eclipselink.order-updates=true`, `eclipselink.persistence-context.commit-order=ID` (statements of a commit are always grouped by table in dependency order) |
| batch versioned updates | `hibernate.jdbc.batch_versioned_data=true` | `hibernate.jdbc.batch_versioned_data=true` | default (versioned updates are batched) | default (versioned updates are batched; only changed columns are updated, so different column sets break a batch) |
| no L2 cache | `hibernate.cache.use_second_level_cache=false` | `hibernate.cache.use_second_level_cache=false` | `openjpa.DataCache=false` | `eclipselink.cache.shared.default=false` |
| no query cache | `hibernate.cache.use_query_cache=false` | `hibernate.cache.use_query_cache=false` | `openjpa.QueryCache=false` | `eclipselink.cache.query-results=false` |
| statement cache | none in Hibernate (driver defaults) | none in Hibernate (driver defaults) | none in OpenJPA (driver defaults); JPQL/finder SQL caches (`QuerySQLCache`, `FinderCache`) at defaults, like Hibernate's query plan cache | `eclipselink.jdbc.cache-statements=false` (the default with an external DataSource) |
| auto-commit already off | `hibernate.connection.provider_disables_autocommit=true` | `hibernate.connection.provider_disables_autocommit=true` | n/a | n/a |
| no statistics / SQL logging | `hibernate.generate_statistics=false`, `show_sql=false`, `format_sql=false`, `use_sql_comments=false` | same as 7 | `openjpa.Log=DefaultLevel=WARN, ... SQL=WARN`, `openjpa.ConnectionFactoryProperties=PrintParameters=false` | `eclipselink.logging.level=WARNING`, `eclipselink.logging.level.sql=OFF`, `eclipselink.logging.parameters=false` |
| bytecode enhancement | none by default; `-Penhance`: build time, lazy initialization + dirty tracking | none by default; `-Penhance`: build time (`hibernate-enhance-maven-plugin`), lazy initialization + dirty tracking | build time (required): `openjpa-maven-plugin`; `openjpa.RuntimeUnenhancedClasses=unsupported`, `openjpa.DynamicEnhancementAgent=false` | `eclipselink.weaving=false` by default (to-one `LAZY` is then loaded eagerly, `getReference` selects); `-Pweave`: static weaving at build time, `eclipselink.weaving=static` |
| exact provider build in `metadata.json` | release version | release version | 4.1: release version; 4.2 (latest Apache snapshot): `bench.provider.build` = the resolved `4.2.0-<timestamp>-<buildNumber>` and `bench.provider.revision` = git revision from the jar, recorded at build time | release version |
| EMF deploys eagerly | default | default | — | `eclipselink.deploy-on-startup=true` (otherwise the session logs in on the first `createEntityManager`) |
| one DB transaction per `EntityTransaction` | default | default | — | `eclipselink.transaction.join-existing=true` (otherwise reads before the first write run on separately acquired connections) |
