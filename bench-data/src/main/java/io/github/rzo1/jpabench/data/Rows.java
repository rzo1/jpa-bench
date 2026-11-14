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
import java.time.LocalDate;
import java.util.List;

/**
 * Plain row records produced by the {@link DataGenerator}. They mirror the DDL, not the entities, so that the
 * data module stays independent of any persistence API.
 */
public final class Rows {

    private Rows() {
    }

    public record CategoryRow(long id, String name) {
    }

    public record ProductRow(long id, String sku, String name, BigDecimal price, List<Long> categoryIds,
                             List<String> tags) {
    }

    public record CustomerRow(long id, String firstName, String lastName, String email, LocalDate createdAt,
                              String street, String city, String zip, String country) {
    }

    public record OrderLineRow(long id, long productId, int lineNo, int quantity, BigDecimal unitPrice) {
    }

    public record OrderRow(long id, int version, long customerId, LocalDate orderDate, String status,
                           BigDecimal total, List<OrderLineRow> lines) {
    }

    /**
     * A person of one of the two inheritance hierarchies. {@code type} is the discriminator value
     * ({@code EMP} or {@code CON}); the columns of the other subtype are {@code null}.
     */
    public record PersonRow(long id, String type, String name, String email, String department, BigDecimal salary,
                            String agency, BigDecimal hourlyRate) {

        public boolean employee() {
            return EMPLOYEE.equals(type);
        }
    }

    public static final String EMPLOYEE = "EMP";
    public static final String CONTRACTOR = "CON";
}
