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

import java.math.BigDecimal;
import java.util.List;
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
import io.github.rzo1.jpabench.model.Customer;
import io.github.rzo1.jpabench.model.OrderLine;
import io.github.rzo1.jpabench.model.OrderStatus;
import io.github.rzo1.jpabench.model.Product;
import io.github.rzo1.jpabench.model.PurchaseOrder;

/**
 * Write benchmarks. Each operation writes {@link WriteState#entities} orders in one transaction of a fresh
 * EntityManager (bulk operations: a fixed id range).
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 10)
@Measurement(iterations = 5, time = 10)
@Fork(value = 2, jvmArgs = {"-Xms2g", "-Xmx2g"})
public class WriteBenchmarks {

    /** Lines per persisted order. */
    static final int LINES = 3;
    /** Rows changed by one bulk update, as a multiple of {@code entities}. */
    static final int BULK_FACTOR = 10;

    private static final OrderStatus[] STATUSES = OrderStatus.values();

    private SplittableRandom random;
    private long updateCursor;
    private int statusCursor;

    @Setup(Level.Trial)
    public void seed() {
        random = new SplittableRandom(1234);
        updateCursor = 1;
    }

    /** Persists new orders with lines; customer and products are referenced with {@code getReference}. */
    @Benchmark
    public void persistBatch(WriteState state, StatementCounters counters, Blackhole bh) {
        int count = state.entities;
        DatasetInfo info = state.fixture.dataset();
        long firstOrder = state.reserveOrderIds(count);
        long firstLine = state.reserveLineIds(count * LINES);
        long[] customers = new long[count];
        long[] products = new long[count * LINES];
        int[] quantities = new int[count * LINES];
        for (int i = 0; i < count; i++) {
            customers[i] = 1 + random.nextInt(info.customers());
        }
        for (int i = 0; i < products.length; i++) {
            products[i] = 1 + random.nextInt(info.products());
            quantities[i] = 1 + random.nextInt(5);
        }
        BigDecimal price = new BigDecimal("9.99");
        bh.consume(state.fixture.inTransaction(em -> {
            long lineId = firstLine;
            for (int i = 0; i < count; i++) {
                PurchaseOrder order = new PurchaseOrder(firstOrder + i, em.getReference(Customer.class, customers[i]),
                        DataGenerator.ORDER_EPOCH, OrderStatus.NEW, BigDecimal.ZERO);
                BigDecimal total = BigDecimal.ZERO;
                for (int n = 0; n < LINES; n++) {
                    int k = i * LINES + n;
                    order.addLine(new OrderLine(lineId++, em.getReference(Product.class, products[k]), n + 1,
                            quantities[k], price));
                    total = total.add(price.multiply(BigDecimal.valueOf(quantities[k])));
                }
                order.setTotal(total);
                em.persist(order);
            }
            return lineId;
        }));
        counters.op();
    }

    /** Loads orders, changes two attributes of each and lets dirty checking write them (versioned updates). */
    @Benchmark
    public void updateDirtyChecking(WriteState state, StatementCounters counters, Blackhole bh) {
        int count = state.entities;
        long from = nextUpdateWindow(state, count);
        OrderStatus status = nextStatus();
        bh.consume(state.fixture.inTransaction(em -> {
            List<PurchaseOrder> orders = em.createQuery(
                            "select o from PurchaseOrder o where o.id between :from and :to order by o.id",
                            PurchaseOrder.class)
                    .setParameter("from", from)
                    .setParameter("to", from + count - 1)
                    .getResultList();
            for (PurchaseOrder order : orders) {
                order.setStatus(status);
                order.setTotal(order.getTotal().add(BigDecimal.ONE));
            }
            return orders.size();
        }));
        counters.op();
    }

    /** Finds the orders inserted for this invocation and removes them; lines are removed by cascade. */
    @Benchmark
    public void removeBatch(DeleteState state, StatementCounters counters, Blackhole bh) {
        bh.consume(state.fixture.inTransaction(em -> {
            List<PurchaseOrder> orders = em.createQuery(
                            "select o from PurchaseOrder o where o.id between :from and :to", PurchaseOrder.class)
                    .setParameter("from", state.victimsFrom)
                    .setParameter("to", state.victimsTo)
                    .getResultList();
            for (PurchaseOrder order : orders) {
                em.remove(order);
            }
            return orders.size();
        }));
        counters.op();
    }

    /** One JPQL bulk update over a range of orders. */
    @Benchmark
    public void bulkUpdate(WriteState state, StatementCounters counters, Blackhole bh) {
        int rows = state.entities * BULK_FACTOR;
        long from = nextUpdateWindow(state, rows);
        OrderStatus status = nextStatus();
        bh.consume(state.fixture.inTransaction(em -> em.createQuery(
                        "update PurchaseOrder o set o.status = :status where o.id between :from and :to")
                .setParameter("status", status)
                .setParameter("from", from)
                .setParameter("to", from + rows - 1)
                .executeUpdate()));
        counters.op();
    }

    /** Two JPQL bulk deletes (lines, then orders) over the orders inserted for this invocation. */
    @Benchmark
    public void bulkDelete(DeleteState state, StatementCounters counters, Blackhole bh) {
        bh.consume(state.fixture.inTransaction(em -> {
            int lines = em.createQuery("delete from OrderLine l where l.order.id between :from and :to")
                    .setParameter("from", state.victimsFrom)
                    .setParameter("to", state.victimsTo)
                    .executeUpdate();
            int orders = em.createQuery("delete from PurchaseOrder o where o.id between :from and :to")
                    .setParameter("from", state.victimsFrom)
                    .setParameter("to", state.victimsTo)
                    .executeUpdate();
            return lines + orders;
        }));
        counters.op();
    }

    /** Cycles through {@code [1, window]} so that only snapshotted orders are modified. */
    private long nextUpdateWindow(WriteState state, int size) {
        if (updateCursor + size - 1 > state.window) {
            updateCursor = 1;
        }
        long from = updateCursor;
        updateCursor += size;
        return from;
    }

    private OrderStatus nextStatus() {
        statusCursor = (statusCursor + 1) % STATUSES.length;
        return STATUSES[statusCursor];
    }
}
