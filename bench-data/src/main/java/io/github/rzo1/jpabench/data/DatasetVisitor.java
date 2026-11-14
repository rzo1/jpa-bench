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

import io.github.rzo1.jpabench.data.Rows.CategoryRow;
import io.github.rzo1.jpabench.data.Rows.CustomerRow;
import io.github.rzo1.jpabench.data.Rows.OrderRow;
import io.github.rzo1.jpabench.data.Rows.PersonRow;
import io.github.rzo1.jpabench.data.Rows.ProductRow;

/**
 * Receives the generated rows in order: categories, products, customers, orders with lines, SINGLE_TABLE persons,
 * JOINED persons.
 */
public interface DatasetVisitor {

    void category(CategoryRow row);

    void product(ProductRow row);

    void customer(CustomerRow row);

    void order(OrderRow row);

    void singleTablePerson(PersonRow row);

    void joinedPerson(PersonRow row);
}
