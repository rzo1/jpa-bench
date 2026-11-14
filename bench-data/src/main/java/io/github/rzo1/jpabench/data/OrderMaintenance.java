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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain JDBC helpers that restore ORDERS and ORDER_LINE between write benchmark iterations. Not measured.
 */
public final class OrderMaintenance {

    /** The mutable columns of an order, captured after loading. */
    public record OrderState(long id, int version, String status, BigDecimal total) {
    }

    private OrderMaintenance() {
    }

    /** Captures the mutable columns of all orders with {@code id <= maxId}. */
    public static List<OrderState> snapshot(Connection connection, long maxId) throws SQLException {
        List<OrderState> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ID, VERSION, STATUS, TOTAL FROM ORDERS WHERE ID <= ? ORDER BY ID")) {
            ps.setLong(1, maxId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new OrderState(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getBigDecimal(4)));
                }
            }
        }
        connection.commit();
        return result;
    }

    /** Deletes the orders and lines above the loaded id range, restores the captured states and commits. */
    public static void reset(Connection connection, long loadedOrders, List<OrderState> snapshot)
            throws SQLException {
        try (PreparedStatement lines = connection.prepareStatement("DELETE FROM ORDER_LINE WHERE ORDER_ID > ?");
             PreparedStatement orders = connection.prepareStatement("DELETE FROM ORDERS WHERE ID > ?");
             PreparedStatement restore = connection.prepareStatement(
                     "UPDATE ORDERS SET VERSION = ?, STATUS = ?, TOTAL = ? WHERE ID = ?")) {
            lines.setLong(1, loadedOrders);
            lines.executeUpdate();
            orders.setLong(1, loadedOrders);
            orders.executeUpdate();
            int pending = 0;
            for (OrderState state : snapshot) {
                restore.setInt(1, state.version());
                restore.setString(2, state.status());
                restore.setBigDecimal(3, state.total());
                restore.setLong(4, state.id());
                restore.addBatch();
                if (++pending == 1_000) {
                    restore.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                restore.executeBatch();
            }
        }
        connection.commit();
    }

    /** Refreshes the planner statistics of all tables with {@code ANALYZE} and commits. */
    public static void analyze(Connection connection, Database database) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ANALYZE");
        }
        connection.commit();
    }

    /**
     * Runs {@code VACUUM ANALYZE} on ORDERS and ORDER_LINE so that every iteration starts from the same physical
     * state. PostgreSQL only.
     */
    public static void vacuumOrders(Connection connection, Database database) throws SQLException {
        if (database != Database.POSTGRESQL) {
            return;
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("VACUUM ANALYZE ORDERS, ORDER_LINE");
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /** Returns the highest ORDER_LINE id. */
    public static long maxOrderLineId(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(MAX(ID), 0) FROM ORDER_LINE");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            long result = rs.getLong(1);
            connection.commit();
            return result;
        }
    }
}
