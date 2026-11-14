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

/**
 * Summary of a loaded dataset: row counts, seed and checksum.
 */
public record DatasetInfo(DatasetSize size, long seed, int categories, int products, int customers, int orders,
                          long orderLines, int singleTablePersons, int joinedPersons, long records, String checksum,
                          long loadMillis) {

    @Override
    public String toString() {
        return "dataset " + size + " seed=" + seed + " checksum=" + checksum + " records=" + records
                + " (categories=" + categories + ", products=" + products + ", customers=" + customers
                + ", orders=" + orders + ", orderLines=" + orderLines + ", stPersons=" + singleTablePersons
                + ", jnPersons=" + joinedPersons + ") loaded in " + loadMillis + " ms";
    }
}
