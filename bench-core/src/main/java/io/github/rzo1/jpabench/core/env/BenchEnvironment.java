/*
 * Copyright the jpa-bench authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.rzo1.jpabench.core.env;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import io.github.rzo1.jpabench.core.metrics.StatementMetrics;
import io.github.rzo1.jpabench.data.Database;
import io.github.rzo1.jpabench.data.DatasetInfo;
import io.github.rzo1.jpabench.data.DatasetLoader;
import io.github.rzo1.jpabench.data.DatasetSize;
import io.github.rzo1.jpabench.data.SqlScript;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;

/**
 * Provider-neutral benchmark environment: database, HikariCP pool with statement metering, JDBC data loading and
 * the {@link EntityManagerFactory} built from the resolved {@link ProviderSettings}.
 */
public final class BenchEnvironment implements AutoCloseable {

    /** Name of the persistence unit in META-INF/persistence.xml. */
    public static final String PERSISTENCE_UNIT = "jpa-bench";

    /**
     * Environment variable switching statement metering {@code on} (default) or {@code off}. Metering adds overhead
     * to every statement, so run.sh measures time with metering off.
     */
    public static final String ENV_METERING = "JPABENCH_METERING";

    private final Database database;
    private final ProviderSettings settings;
    private final DatabaseServer server;
    private final HikariDataSource pool;
    private final DataSource metered;
    private final boolean metering;

    private BenchEnvironment(Database database, ProviderSettings settings, DatabaseServer server,
                             HikariDataSource pool) {
        this.database = database;
        this.settings = settings;
        this.server = server;
        this.pool = pool;
        this.metering = meteringEnabled();
        this.metered = metering
                ? ProxyDataSourceBuilder.create("jpa-bench", pool).listener(StatementMetrics.INSTANCE)
                        .methodListener(StatementMetrics.INSTANCE).build()
                : pool;
    }

    /** Whether statement metering is on for this JVM ({@value #ENV_METERING}, default on). */
    public static boolean meteringEnabled() {
        String value = System.getenv(ENV_METERING);
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> throw new IllegalArgumentException(ENV_METERING + " must be on or off, not " + value);
        };
    }

    /** Starts the database and the connection pool. */
    public static BenchEnvironment start(Database database) {
        ProviderSettings settings = ProviderSettings.load(database);
        DatabaseServer server = DatabaseServer.start(database, settings);
        try {
            HikariConfig config = new HikariConfig();
            config.setPoolName("jpa-bench");
            config.setJdbcUrl(server.jdbcUrl());
            config.setUsername(server.user());
            config.setPassword(server.password());
            config.setMaximumPoolSize(settings.poolSize());
            config.setMinimumIdle(settings.poolSize());
            config.setAutoCommit(Boolean.parseBoolean(settings.harness("bench.pool.autoCommit")));
            config.setTransactionIsolation(settings.harness("bench.pool.isolation"));
            return new BenchEnvironment(database, settings, server, new HikariDataSource(config));
        } catch (RuntimeException e) {
            server.close();
            throw e;
        }
    }

    public Database database() {
        return database;
    }

    public ProviderSettings settings() {
        return settings;
    }

    /** Returns the unmetered pool for the harness' own JDBC work. */
    public DataSource rawDataSource() {
        return pool;
    }

    /** Whether the provider's statements are counted (see {@link #ENV_METERING}). */
    public boolean metering() {
        return metering;
    }

    /** Returns the DataSource handed to the provider: the metered pool, or the plain pool if metering is off. */
    public DataSource meteredDataSource() {
        return metered;
    }

    /** Recreates the schema without data. */
    public void createSchema() {
        try (Connection connection = pool.getConnection()) {
            SqlScript.createSchema(connection, database);
        } catch (SQLException e) {
            throw new IllegalStateException("Creating the schema failed", e);
        }
    }

    /** Recreates the schema and loads the dataset with plain JDBC. */
    public DatasetInfo loadDataset(DatasetSize size, long seed) {
        try (Connection connection = pool.getConnection()) {
            DatasetInfo info = DatasetLoader.load(connection, database, size, seed);
            log(info.toString());
            return info;
        } catch (SQLException e) {
            throw new IllegalStateException("Loading the dataset failed", e);
        }
    }

    /** The property map handed to {@code Persistence.createEntityManagerFactory}. */
    public Map<String, Object> entityManagerFactoryProperties() {
        Map<String, Object> properties = new HashMap<>(settings.providerProperties());
        for (String key : settings.dataSourceKeys()) {
            properties.put(key, metered);
        }
        return properties;
    }

    public EntityManagerFactory createEntityManagerFactory() {
        return Persistence.createEntityManagerFactory(PERSISTENCE_UNIT, entityManagerFactoryProperties());
    }

    /** PostgreSQL settings logged with every trial. */
    private static final String[] PG_SETTINGS = {
        "shared_buffers", "work_mem", "effective_cache_size", "synchronous_commit", "fsync", "jit", "autovacuum",
        "max_connections", "random_page_cost"
    };

    /** Returns a one-line description of provider, database and settings. */
    public String describe() {
        String product;
        try (Connection connection = pool.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            product = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion() + ", driver "
                    + meta.getDriverName() + " " + meta.getDriverVersion() + serverSettings(connection);
            connection.rollback();
        } catch (SQLException e) {
            product = "unknown (" + e.getMessage() + ")";
        }
        return "provider " + settings.providerName() + " " + settings.providerVersion() + " on "
                + server.description() + " [" + product + "] metering " + (metering ? "on" : "off") + " settings "
                + settings.providerProperties();
    }

    /** Returns the server version and {@link #PG_SETTINGS} on PostgreSQL, an empty string otherwise. */
    private String serverSettings(Connection connection) throws SQLException {
        if (database != Database.POSTGRESQL) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery("SELECT version()")) {
                rs.next();
                sb.append(", server ").append(rs.getString(1));
            }
            for (String name : PG_SETTINGS) {
                try (ResultSet rs = statement.executeQuery("SHOW " + name)) {
                    rs.next();
                    sb.append(", ").append(name).append('=').append(rs.getString(1));
                }
            }
        }
        return sb.toString();
    }

    public void log(String message) {
        System.out.println("# jpa-bench: " + message);
    }

    @Override
    public void close() {
        try {
            pool.close();
        } finally {
            server.close();
        }
    }
}
