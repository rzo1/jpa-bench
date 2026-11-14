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
import java.sql.SQLException;
import java.util.HashMap;
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
 * Provider-neutral benchmark environment: starts the database, creates the HikariCP pool, wraps it with the
 * statement metering proxy, loads data with plain JDBC and builds the {@link EntityManagerFactory} only from the
 * resolved {@link ProviderSettings}.
 */
public final class BenchEnvironment implements AutoCloseable {

    /** The name of the persistence unit in bench-core's META-INF/persistence.xml. */
    public static final String PERSISTENCE_UNIT = "jpa-bench";

    private final Database database;
    private final ProviderSettings settings;
    private final DatabaseServer server;
    private final HikariDataSource pool;
    private final DataSource metered;

    private BenchEnvironment(Database database, ProviderSettings settings, DatabaseServer server,
                             HikariDataSource pool) {
        this.database = database;
        this.settings = settings;
        this.server = server;
        this.pool = pool;
        this.metered = ProxyDataSourceBuilder.create("jpa-bench", pool)
                .listener(StatementMetrics.INSTANCE)
                .build();
    }

    /** Starts the database and the connection pool. */
    public static BenchEnvironment start(Database database) {
        ProviderSettings settings = ProviderSettings.load(database);
        DatabaseServer server = DatabaseServer.start(database);
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

    /** The pool without metering, for the harness' own JDBC work. */
    public DataSource rawDataSource() {
        return pool;
    }

    /** The pool wrapped with the statement metering proxy; the only DataSource a provider ever sees. */
    public DataSource meteredDataSource() {
        return metered;
    }

    /** (Re)creates the schema without data. */
    public void createSchema() {
        try (Connection connection = pool.getConnection()) {
            SqlScript.createSchema(connection, database);
        } catch (SQLException e) {
            throw new IllegalStateException("Creating the schema failed", e);
        }
    }

    /** (Re)creates the schema and loads the dataset with plain JDBC. */
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

    /** One line describing provider, database and settings, printed at the start of every trial. */
    public String describe() {
        String product;
        try (Connection connection = pool.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            product = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion() + ", driver "
                    + meta.getDriverName() + " " + meta.getDriverVersion();
            connection.rollback();
        } catch (SQLException e) {
            product = "unknown (" + e.getMessage() + ")";
        }
        return "provider " + settings.providerName() + " " + settings.providerVersion() + " on "
                + server.description() + " [" + product + "] settings " + settings.providerProperties();
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
