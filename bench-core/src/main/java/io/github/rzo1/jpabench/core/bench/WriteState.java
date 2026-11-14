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

import java.util.List;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import io.github.rzo1.jpabench.data.DatasetInfo;
import io.github.rzo1.jpabench.data.OrderMaintenance;
import io.github.rzo1.jpabench.data.OrderMaintenance.OrderState;

/**
 * State of the write benchmarks. Before every iteration ORDERS and ORDER_LINE are reset with plain JDBC: inserted
 * rows are deleted, orders {@code 1..window} are restored and, on PostgreSQL, both tables are vacuumed.
 */
@State(Scope.Benchmark)
public class WriteState {

    /** Highest order id the update benchmarks touch. */
    static final int MAX_WINDOW = 5_000;

    /** Database id: h2 or postgresql. */
    @Param({"h2"})
    public String db;

    /** Dataset size: S, M or L. */
    @Param({"S"})
    public String dataset;

    /** Entities (orders) written per operation. */
    @Param({"50"})
    public int entities;

    Fixture fixture;
    long window;
    private List<OrderState> snapshot;
    private long nextOrderId;
    private long nextLineId;

    @Setup(Level.Trial)
    public void setUp() {
        fixture = Fixture.withData(db, dataset);
        DatasetInfo info = fixture.dataset();
        window = Math.min(info.orders(), MAX_WINDOW);
        snapshot = fixture.withRawConnection(c -> OrderMaintenance.snapshot(c, window));
        nextOrderId = info.orders() + 1L;
        nextLineId = info.orderLines() + 1L;
    }

    @Setup(Level.Iteration)
    public void resetTables() {
        fixture.withRawConnection(c -> {
            OrderMaintenance.reset(c, fixture.dataset().orders(), snapshot);
            OrderMaintenance.vacuumOrders(c, fixture.env().database());
            return null;
        });
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fixture.close();
    }

    /** Reserves {@code count} unused order ids and returns the first. */
    long reserveOrderIds(int count) {
        long first = nextOrderId;
        nextOrderId += count;
        return first;
    }

    /** Reserves {@code count} unused order line ids and returns the first. */
    long reserveLineIds(int count) {
        long first = nextLineId;
        nextLineId += count;
        return first;
    }
}
