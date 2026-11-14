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
package io.github.rzo1.jpabench.core.bench;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

import io.github.rzo1.jpabench.core.env.BenchEnvironment;
import io.github.rzo1.jpabench.data.DataGenerator;
import io.github.rzo1.jpabench.data.Database;
import io.github.rzo1.jpabench.data.DatasetInfo;
import io.github.rzo1.jpabench.data.DatasetSize;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

/**
 * What a benchmark state holds for one trial (one fork): the environment, the loaded dataset and the
 * {@link EntityManagerFactory}, which is created once per trial.
 */
public final class Fixture implements AutoCloseable {

    private final BenchEnvironment env;
    private final DatasetInfo dataset;
    private final EntityManagerFactory emf;

    private Fixture(BenchEnvironment env, DatasetInfo dataset, EntityManagerFactory emf) {
        this.env = env;
        this.dataset = dataset;
        this.emf = emf;
    }

    /** Starts the database, loads the dataset and creates the EMF. */
    public static Fixture withData(String db, String dataset) {
        BenchEnvironment env = BenchEnvironment.start(Database.of(db));
        try {
            env.log(env.describe());
            DatasetInfo info = env.loadDataset(DatasetSize.valueOf(dataset), DataGenerator.DEFAULT_SEED);
            return new Fixture(env, info, env.createEntityManagerFactory());
        } catch (RuntimeException e) {
            env.close();
            throw e;
        }
    }

    public BenchEnvironment env() {
        return env;
    }

    public DatasetInfo dataset() {
        return dataset;
    }

    public EntityManagerFactory emf() {
        return emf;
    }

    /**
     * Runs {@code work} in a fresh EntityManager and a resource-local transaction, then closes the EntityManager.
     * Every benchmark uses this, reads included, so every provider runs with the same transaction demarcation.
     */
    public <T> T inTransaction(Function<EntityManager, T> work) {
        EntityManager em = emf.createEntityManager();
        try {
            EntityTransaction tx = em.getTransaction();
            tx.begin();
            try {
                T result = work.apply(em);
                tx.commit();
                return result;
            } finally {
                if (tx.isActive()) {
                    tx.rollback();
                }
            }
        } finally {
            em.close();
        }
    }

    /** Harness JDBC work on the raw (unmetered) pool. */
    public <T> T withRawConnection(SqlWork<T> work) {
        try (Connection connection = env.rawDataSource().getConnection()) {
            return work.run(connection);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    @Override
    public void close() {
        try {
            if (emf != null && emf.isOpen()) {
                emf.close();
            }
        } finally {
            env.close();
        }
    }
}
