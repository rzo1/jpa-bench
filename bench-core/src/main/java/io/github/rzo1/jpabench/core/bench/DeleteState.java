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
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import io.github.rzo1.jpabench.data.DataGenerator;
import io.github.rzo1.jpabench.data.DatasetInfo;
import io.github.rzo1.jpabench.data.DatasetLoader;
import io.github.rzo1.jpabench.data.Rows.OrderLineRow;
import io.github.rzo1.jpabench.data.Rows.OrderRow;

/**
 * State of the delete benchmarks. Before every invocation, {@link WriteState#entities} orders with {@value #LINES}
 * lines each are inserted with plain JDBC for the benchmark to delete. The setup is neither timed nor counted.
 */
@State(Scope.Benchmark)
public class DeleteState extends WriteState {

    static final int LINES = 3;

    long victimsFrom;
    long victimsTo;
    private SplittableRandom random;

    @Setup(Level.Trial)
    public void seedVictims() {
        random = new SplittableRandom(815);
    }

    @Setup(Level.Invocation)
    public void insertVictims() {
        DatasetInfo info = fixture.dataset();
        long firstOrder = reserveOrderIds(entities);
        long lineId = reserveLineIds(entities * LINES);
        List<OrderRow> orders = new ArrayList<>(entities);
        for (int i = 0; i < entities; i++) {
            List<OrderLineRow> lines = new ArrayList<>(LINES);
            BigDecimal total = BigDecimal.ZERO;
            for (int n = 1; n <= LINES; n++) {
                BigDecimal price = DataGenerator.cents(random, 100, 10_000);
                int quantity = 1 + random.nextInt(5);
                total = total.add(price.multiply(BigDecimal.valueOf(quantity)));
                lines.add(new OrderLineRow(lineId++, 1 + random.nextInt(info.products()), n, quantity, price));
            }
            orders.add(new OrderRow(firstOrder + i, 0, 1 + random.nextInt(info.customers()),
                    DataGenerator.ORDER_EPOCH.plusDays(random.nextInt(DataGenerator.ORDER_DAYS)), "NEW", total,
                    lines));
        }
        fixture.withRawConnection(c -> {
            DatasetLoader.insertOrders(c, orders);
            c.commit();
            return null;
        });
        victimsFrom = firstOrder;
        victimsTo = firstOrder + entities - 1;
    }
}
