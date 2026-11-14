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
package io.github.rzo1.jpabench.data;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import io.github.rzo1.jpabench.data.Rows.CategoryRow;
import io.github.rzo1.jpabench.data.Rows.CustomerRow;
import io.github.rzo1.jpabench.data.Rows.OrderLineRow;
import io.github.rzo1.jpabench.data.Rows.OrderRow;
import io.github.rzo1.jpabench.data.Rows.PersonRow;
import io.github.rzo1.jpabench.data.Rows.ProductRow;

/**
 * Loads a generated dataset with plain JDBC batches. The loader never goes through a JPA provider, so every
 * provider is benchmarked against byte-identical data.
 */
public final class DatasetLoader {

    private static final int BATCH = 1_000;

    static final String INSERT_ORDER =
            "INSERT INTO ORDERS (ID, VERSION, CUSTOMER_ID, ORDER_DATE, STATUS, TOTAL) VALUES (?, ?, ?, ?, ?, ?)";
    static final String INSERT_ORDER_LINE =
            "INSERT INTO ORDER_LINE (ID, ORDER_ID, PRODUCT_ID, LINE_NO, QUANTITY, UNIT_PRICE) VALUES (?, ?, ?, ?, ?, ?)";

    private DatasetLoader() {
    }

    /**
     * (Re)creates the schema of {@code database}, loads the dataset of the given size and seed and refreshes the
     * planner statistics ({@link OrderMaintenance#analyze}). The connection must have auto-commit disabled.
     */
    public static DatasetInfo load(Connection connection, Database database, DatasetSize size, long seed)
            throws SQLException {
        long start = System.nanoTime();
        SqlScript.createSchema(connection, database);
        try (BatchingVisitor visitor = new BatchingVisitor(connection)) {
            Checksum checksum = new DataGenerator(seed, size).generate(visitor);
            visitor.flushAll();
            connection.commit();
            OrderMaintenance.analyze(connection, database);
            long millis = (System.nanoTime() - start) / 1_000_000;
            return new DatasetInfo(size, seed, DatasetSize.CATEGORIES, size.products(), size.customers(),
                    size.orders(), visitor.orderLines, size.persons(), size.persons(), checksum.records(),
                    checksum.hex(), millis);
        }
    }

    /**
     * Inserts orders with their lines (used by write benchmarks to prepare rows they delete). Does not commit.
     */
    public static void insertOrders(Connection connection, List<OrderRow> orders) throws SQLException {
        try (PreparedStatement order = connection.prepareStatement(INSERT_ORDER);
             PreparedStatement line = connection.prepareStatement(INSERT_ORDER_LINE)) {
            for (OrderRow row : orders) {
                bindOrder(order, row);
                order.addBatch();
            }
            order.executeBatch();
            for (OrderRow row : orders) {
                for (OrderLineRow l : row.lines()) {
                    bindLine(line, row.id(), l);
                    line.addBatch();
                }
            }
            line.executeBatch();
        }
    }

    static void bindOrder(PreparedStatement ps, OrderRow row) throws SQLException {
        ps.setLong(1, row.id());
        ps.setInt(2, row.version());
        ps.setLong(3, row.customerId());
        ps.setDate(4, Date.valueOf(row.orderDate()));
        ps.setString(5, row.status());
        ps.setBigDecimal(6, row.total());
    }

    static void bindLine(PreparedStatement ps, long orderId, OrderLineRow l) throws SQLException {
        ps.setLong(1, l.id());
        ps.setLong(2, orderId);
        ps.setLong(3, l.productId());
        ps.setInt(4, l.lineNo());
        ps.setInt(5, l.quantity());
        ps.setBigDecimal(6, l.unitPrice());
    }

    /**
     * One batched insert into one table. Before a batch is executed, the batches of the tables it references are
     * executed, so foreign keys are always satisfied while every table is still inserted in full batches.
     */
    private static final class Batch implements AutoCloseable {
        private final PreparedStatement statement;
        private final List<Batch> parents;
        private int pending;

        Batch(Connection connection, String sql, List<Batch> parents) throws SQLException {
            this.statement = connection.prepareStatement(sql);
            this.parents = parents;
        }

        PreparedStatement statement() {
            return statement;
        }

        void add() throws SQLException {
            statement.addBatch();
            if (++pending == BATCH) {
                flush();
            }
        }

        void flush() throws SQLException {
            for (Batch parent : parents) {
                parent.flush();
            }
            if (pending > 0) {
                statement.executeBatch();
                pending = 0;
            }
        }

        @Override
        public void close() throws SQLException {
            statement.close();
        }
    }

    private static final class BatchingVisitor implements DatasetVisitor, AutoCloseable {
        private final Connection connection;
        private final List<Batch> batches = new ArrayList<>();
        private final Batch category;
        private final Batch product;
        private final Batch productCategory;
        private final Batch productTag;
        private final Batch customer;
        private final Batch order;
        private final Batch orderLine;
        private final Batch stPerson;
        private final Batch jnPerson;
        private final Batch jnEmployee;
        private final Batch jnContractor;
        private long orderLines;

        BatchingVisitor(Connection connection) throws SQLException {
            this.connection = connection;
            category = batch("INSERT INTO CATEGORY (ID, NAME) VALUES (?, ?)");
            product = batch("INSERT INTO PRODUCT (ID, SKU, NAME, PRICE) VALUES (?, ?, ?, ?)");
            productCategory = batch("INSERT INTO PRODUCT_CATEGORY (PRODUCT_ID, CATEGORY_ID) VALUES (?, ?)",
                    product, category);
            productTag = batch("INSERT INTO PRODUCT_TAG (PRODUCT_ID, TAG) VALUES (?, ?)", product);
            customer = batch("INSERT INTO CUSTOMER (ID, FIRST_NAME, LAST_NAME, EMAIL, CREATED_AT, STREET, CITY, ZIP,"
                    + " COUNTRY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
            order = batch(INSERT_ORDER, customer);
            orderLine = batch(INSERT_ORDER_LINE, order, product);
            stPerson = batch("INSERT INTO ST_PERSON (ID, PTYPE, NAME, EMAIL, DEPARTMENT, SALARY, AGENCY, HOURLY_RATE)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)");
            jnPerson = batch("INSERT INTO JN_PERSON (ID, PTYPE, NAME, EMAIL) VALUES (?, ?, ?, ?)");
            jnEmployee = batch("INSERT INTO JN_EMPLOYEE (ID, DEPARTMENT, SALARY) VALUES (?, ?, ?)", jnPerson);
            jnContractor = batch("INSERT INTO JN_CONTRACTOR (ID, AGENCY, HOURLY_RATE) VALUES (?, ?, ?)", jnPerson);
        }

        private Batch batch(String sql, Batch... parents) throws SQLException {
            Batch batch = new Batch(connection, sql, List.of(parents));
            batches.add(batch);
            return batch;
        }

        @Override
        public void category(CategoryRow row) {
            run(() -> {
                PreparedStatement ps = category.statement();
                ps.setLong(1, row.id());
                ps.setString(2, row.name());
                category.add();
            });
        }

        @Override
        public void product(ProductRow row) {
            run(() -> {
                PreparedStatement ps = product.statement();
                ps.setLong(1, row.id());
                ps.setString(2, row.sku());
                ps.setString(3, row.name());
                ps.setBigDecimal(4, row.price());
                product.add();
                for (long categoryId : row.categoryIds()) {
                    productCategory.statement().setLong(1, row.id());
                    productCategory.statement().setLong(2, categoryId);
                    productCategory.add();
                }
                for (String tag : row.tags()) {
                    productTag.statement().setLong(1, row.id());
                    productTag.statement().setString(2, tag);
                    productTag.add();
                }
            });
        }

        @Override
        public void customer(CustomerRow row) {
            run(() -> {
                PreparedStatement ps = customer.statement();
                ps.setLong(1, row.id());
                ps.setString(2, row.firstName());
                ps.setString(3, row.lastName());
                ps.setString(4, row.email());
                ps.setDate(5, Date.valueOf(row.createdAt()));
                ps.setString(6, row.street());
                ps.setString(7, row.city());
                ps.setString(8, row.zip());
                ps.setString(9, row.country());
                customer.add();
            });
        }

        @Override
        public void order(OrderRow row) {
            run(() -> {
                bindOrder(order.statement(), row);
                order.add();
                for (OrderLineRow l : row.lines()) {
                    bindLine(orderLine.statement(), row.id(), l);
                    orderLine.add();
                    orderLines++;
                }
            });
        }

        @Override
        public void singleTablePerson(PersonRow row) {
            run(() -> {
                PreparedStatement ps = stPerson.statement();
                ps.setLong(1, row.id());
                ps.setString(2, row.type());
                ps.setString(3, row.name());
                ps.setString(4, row.email());
                setNullable(ps, 5, row.department());
                setNullable(ps, 6, row.salary());
                setNullable(ps, 7, row.agency());
                setNullable(ps, 8, row.hourlyRate());
                stPerson.add();
            });
        }

        @Override
        public void joinedPerson(PersonRow row) {
            run(() -> {
                PreparedStatement ps = jnPerson.statement();
                ps.setLong(1, row.id());
                ps.setString(2, row.type());
                ps.setString(3, row.name());
                ps.setString(4, row.email());
                jnPerson.add();
                if (row.employee()) {
                    jnEmployee.statement().setLong(1, row.id());
                    jnEmployee.statement().setString(2, row.department());
                    jnEmployee.statement().setBigDecimal(3, row.salary());
                    jnEmployee.add();
                } else {
                    jnContractor.statement().setLong(1, row.id());
                    jnContractor.statement().setString(2, row.agency());
                    jnContractor.statement().setBigDecimal(3, row.hourlyRate());
                    jnContractor.add();
                }
            });
        }

        /** Flushes all batches in insertion (= foreign key) order. */
        void flushAll() throws SQLException {
            for (Batch batch : batches) {
                batch.flush();
            }
        }

        @Override
        public void close() throws SQLException {
            SQLException failure = null;
            for (Batch batch : batches) {
                try {
                    batch.close();
                } catch (SQLException e) {
                    failure = e;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
            if (value == null) {
                ps.setNull(index, Types.VARCHAR);
            } else {
                ps.setString(index, value);
            }
        }

        private static void setNullable(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
            if (value == null) {
                ps.setNull(index, Types.NUMERIC);
            } else {
                ps.setBigDecimal(index, value);
            }
        }

        private static void run(SqlAction action) {
            try {
                action.run();
            } catch (SQLException e) {
                throw new IllegalStateException("Loading the dataset failed", e);
            }
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
