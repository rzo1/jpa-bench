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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import io.github.rzo1.jpabench.data.Rows.CategoryRow;
import io.github.rzo1.jpabench.data.Rows.CustomerRow;
import io.github.rzo1.jpabench.data.Rows.OrderRow;
import io.github.rzo1.jpabench.data.Rows.PersonRow;
import io.github.rzo1.jpabench.data.Rows.ProductRow;

class DatasetLoaderTest {

    private static final DatasetVisitor IGNORE = new DatasetVisitor() {
        @Override
        public void category(CategoryRow row) {
        }

        @Override
        public void product(ProductRow row) {
        }

        @Override
        public void customer(CustomerRow row) {
        }

        @Override
        public void order(OrderRow row) {
        }

        @Override
        public void singleTablePerson(PersonRow row) {
        }

        @Override
        public void joinedPerson(PersonRow row) {
        }
    };

    @Test
    void sameSeedProducesSameData() {
        String first = new DataGenerator(DataGenerator.DEFAULT_SEED, DatasetSize.S).generate(IGNORE).hex();
        String second = new DataGenerator(DataGenerator.DEFAULT_SEED, DatasetSize.S).generate(IGNORE).hex();
        String other = new DataGenerator(DataGenerator.DEFAULT_SEED + 1, DatasetSize.S).generate(IGNORE).hex();
        assertEquals(first, second);
        assertNotEquals(first, other);
    }

    /**
     * Golden checksum of dataset S with the default seed. A failure means the generated data changed and results
     * are no longer comparable with earlier runs.
     */
    @Test
    void defaultDatasetMatchesGoldenChecksum() {
        assertEquals(20260101L, DataGenerator.DEFAULT_SEED);
        Checksum checksum = new DataGenerator(DataGenerator.DEFAULT_SEED, DatasetSize.S).generate(IGNORE);
        assertEquals("17f556d4e3d90c3d", checksum.hex());
    }

    @Test
    void generatesTheSameDataInEveryLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertEquals("17f556d4e3d90c3d",
                    new DataGenerator(DataGenerator.DEFAULT_SEED, DatasetSize.S).generate(IGNORE).hex());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void loadsIntoH2() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:loader-test;DB_CLOSE_DELAY=-1")) {
            connection.setAutoCommit(false);
            DatasetInfo info = DatasetLoader.load(connection, Database.H2, DatasetSize.S, DataGenerator.DEFAULT_SEED);
            assertEquals(DatasetSize.S.customers(), count(connection, "CUSTOMER"));
            assertEquals(DatasetSize.S.orders(), count(connection, "ORDERS"));
            assertEquals(info.orderLines(), count(connection, "ORDER_LINE"));
            assertEquals(DatasetSize.S.persons(), count(connection, "ST_PERSON"));
            assertEquals(DatasetSize.S.persons(),
                    count(connection, "JN_EMPLOYEE") + count(connection, "JN_CONTRACTOR"));

            List<OrderMaintenance.OrderState> snapshot = OrderMaintenance.snapshot(connection, 10);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE ORDERS SET VERSION = 7 WHERE ID <= 10");
            }
            OrderMaintenance.reset(connection, info.orders(), snapshot);
            assertEquals(0, count(connection, "ORDERS WHERE VERSION <> 0"));
        }
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
