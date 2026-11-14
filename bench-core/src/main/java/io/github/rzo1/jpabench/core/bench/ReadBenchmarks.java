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

import static io.github.rzo1.jpabench.core.bench.ReadOperations.AGGREGATION_DAYS;
import static io.github.rzo1.jpabench.core.bench.ReadOperations.CRITERIA_DAYS;
import static io.github.rzo1.jpabench.core.bench.ReadOperations.CRITERIA_EPOCH;
import static io.github.rzo1.jpabench.core.bench.ReadOperations.MAX_PAGES;
import static io.github.rzo1.jpabench.core.bench.ReadOperations.NAVIGATION_ORDERS;
import static io.github.rzo1.jpabench.core.bench.ReadOperations.PERSONS;
import static io.github.rzo1.jpabench.core.bench.ReadOperations.PRODUCTS;
import static io.github.rzo1.jpabench.core.bench.ReadOperations.PROJECTION_DAYS;
import static io.github.rzo1.jpabench.core.bench.ReadOperations.STREAM_ORDERS;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import io.github.rzo1.jpabench.core.metrics.StatementCounters;
import io.github.rzo1.jpabench.data.DataGenerator;
import io.github.rzo1.jpabench.data.DatasetInfo;

/**
 * Read benchmarks. Every operation ({@link ReadOperations}) uses a fresh EntityManager and transaction, picks its
 * input with a seeded random generator and touches exactly the data the operation is supposed to load, folding it
 * into a checksum that is consumed by the {@link Blackhole}. Before the first iteration of every trial,
 * {@link ReadState} runs all operations once with fixed inputs and logs their checksums ({@link Verification}), so
 * run.sh can check that every provider returns the same results.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 10)
@Measurement(iterations = 5, time = 10)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class ReadBenchmarks {

    private SplittableRandom random;

    @Setup(Level.Trial)
    public void seed() {
        random = new SplittableRandom(4711);
    }

    private long randomId(long max) {
        return 1 + random.nextLong(max);
    }

    /** First id of a window of {@code size} consecutive ids within {@code [1, max]}. */
    private long randomWindow(long max, int size) {
        return 1 + random.nextLong(Math.max(1, max - size + 1));
    }

    @Benchmark
    public void findById(ReadState state, StatementCounters counters, Blackhole bh) {
        long id = randomId(state.fixture.dataset().customers());
        bh.consume(ReadOperations.findById(state.fixture, id));
        counters.op();
    }

    @Benchmark
    public void jpqlByIndexedAttribute(ReadState state, StatementCounters counters, Blackhole bh) {
        long id = randomId(state.fixture.dataset().customers());
        bh.consume(ReadOperations.jpqlByIndexedAttribute(state.fixture, id));
        counters.op();
    }

    /** Loads orders and navigates the lazy customer and lines associations one by one (N+1 selects). */
    @Benchmark
    public void lazyNavigationNPlusOne(ReadState state, StatementCounters counters, Blackhole bh) {
        long from = randomWindow(state.fixture.dataset().orders(), NAVIGATION_ORDERS);
        bh.consume(ReadOperations.lazyNavigationNPlusOne(state.fixture, from));
        counters.op();
    }

    /** Loads the same data as {@link #lazyNavigationNPlusOne} with fetch joins in one query. */
    @Benchmark
    public void joinFetch(ReadState state, StatementCounters counters, Blackhole bh) {
        long from = randomWindow(state.fixture.dataset().orders(), NAVIGATION_ORDERS);
        bh.consume(ReadOperations.joinFetch(state.fixture, from));
        counters.op();
    }

    @Benchmark
    public void manyToManyAndElementCollection(ReadState state, StatementCounters counters, Blackhole bh) {
        long from = randomWindow(state.fixture.dataset().products(), PRODUCTS);
        bh.consume(ReadOperations.manyToManyAndElementCollection(state.fixture, from));
        counters.op();
    }

    @Benchmark
    public void inheritanceSingleTableQuery(ReadState state, StatementCounters counters, Blackhole bh) {
        long from = randomWindow(state.fixture.dataset().singleTablePersons(), PERSONS);
        bh.consume(ReadOperations.inheritanceSingleTableQuery(state.fixture, from));
        counters.op();
    }

    @Benchmark
    public void inheritanceJoinedQuery(ReadState state, StatementCounters counters, Blackhole bh) {
        long from = randomWindow(state.fixture.dataset().joinedPersons(), PERSONS);
        bh.consume(ReadOperations.inheritanceJoinedQuery(state.fixture, from));
        counters.op();
    }

    /** Customers of one city created after a date, ordered by last name: built with the Criteria API. */
    @Benchmark
    public void criteriaQuery(ReadState state, StatementCounters counters, Blackhole bh) {
        String city = DataGenerator.CITIES.get(random.nextInt(DataGenerator.CITIES.size()));
        bh.consume(ReadOperations.criteriaQuery(state.fixture, city,
                CRITERIA_EPOCH.plusDays(random.nextInt(CRITERIA_DAYS))));
        counters.op();
    }

    /** One page of delivered orders, newest first, plus the count query a paging UI needs. */
    @Benchmark
    public void pagination(ReadState state, StatementCounters counters, Blackhole bh) {
        bh.consume(ReadOperations.pagination(state.fixture, random.nextInt(MAX_PAGES)));
        counters.op();
    }

    @Benchmark
    public void dtoProjection(ReadState state, StatementCounters counters, Blackhole bh) {
        bh.consume(ReadOperations.dtoProjection(state.fixture, DataGenerator.ORDER_EPOCH.plusDays(
                random.nextInt(DataGenerator.ORDER_DAYS - PROJECTION_DAYS))));
        counters.op();
    }

    @Benchmark
    public void aggregationGroupBy(ReadState state, StatementCounters counters, Blackhole bh) {
        bh.consume(ReadOperations.aggregationGroupBy(state.fixture, DataGenerator.ORDER_EPOCH.plusDays(
                random.nextInt(DataGenerator.ORDER_DAYS - AGGREGATION_DAYS))));
        counters.op();
    }

    /** Streams a large result with {@code getResultStream()} and reads every row. */
    @Benchmark
    public void resultStream(ReadState state, StatementCounters counters, Blackhole bh) {
        DatasetInfo dataset = state.fixture.dataset();
        int rows = Math.min(STREAM_ORDERS, dataset.orders());
        bh.consume(ReadOperations.resultStream(state.fixture, randomWindow(dataset.orders(), rows), rows));
        counters.op();
    }
}
