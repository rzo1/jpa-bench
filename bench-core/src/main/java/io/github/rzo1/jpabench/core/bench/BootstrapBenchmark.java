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

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import io.github.rzo1.jpabench.core.env.BenchEnvironment;
import io.github.rzo1.jpabench.core.metrics.StatementCounters;
import io.github.rzo1.jpabench.data.Database;
import io.github.rzo1.jpabench.model.Customer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Time to a usable EntityManagerFactory: create the EMF, read its metamodel, open an EntityManager, run one
 * {@code find} and close everything. Measured as single shots; the first measured shot of each fork is the
 * cold start. run.sh runs this benchmark separately with {@code -bm ss}.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 20)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class BootstrapBenchmark {

    /** Holds the database and the schema (no data), not the EMF. */
    @State(Scope.Benchmark)
    public static class BootstrapState {

        /** Database id: h2 or postgresql. */
        @Param({"h2"})
        public String db;

        BenchEnvironment env;

        @Setup(Level.Trial)
        public void setUp() {
            env = BenchEnvironment.start(Database.of(db));
            env.log(env.describe());
            env.createSchema();
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            env.close();
        }
    }

    @Benchmark
    public void emfBootstrap(BootstrapState state, StatementCounters counters, Blackhole bh) {
        EntityManagerFactory emf = state.env.createEntityManagerFactory();
        try {
            bh.consume(emf.getMetamodel().getEntities().size());
            EntityManager em = emf.createEntityManager();
            try {
                bh.consume(em.find(Customer.class, 1L));
            } finally {
                em.close();
            }
        } finally {
            emf.close();
        }
        counters.op();
    }
}
