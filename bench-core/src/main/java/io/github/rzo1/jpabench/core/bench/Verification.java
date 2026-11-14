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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongUnaryOperator;

import io.github.rzo1.jpabench.data.DataGenerator;
import io.github.rzo1.jpabench.data.DatasetInfo;

/**
 * Runs every read operation with fixed inputs and logs one checksum per operation as
 * {@code # jpa-bench: verify <operation>=<hex>}. run.sh reports operations whose checksums differ between providers.
 */
final class Verification {

    /** Inputs per operation: start, middle and end of the dataset. */
    private static final int SAMPLES = 3;

    private Verification() {
    }

    /** Returns the checksums of all read operations in a stable order. */
    static Map<String, String> readChecksums(Fixture fixture) {
        DatasetInfo d = fixture.dataset();
        Map<String, String> result = new LinkedHashMap<>();
        int streamRows = Math.min(ReadOperations.STREAM_ORDERS, d.orders());
        put(result, "findById", d.customers(), 1, id -> ReadOperations.findById(fixture, id));
        put(result, "jpqlByIndexedAttribute", d.customers(), 1,
                id -> ReadOperations.jpqlByIndexedAttribute(fixture, id));
        put(result, "lazyNavigationNPlusOne", d.orders(), ReadOperations.NAVIGATION_ORDERS,
                from -> ReadOperations.lazyNavigationNPlusOne(fixture, from));
        put(result, "joinFetch", d.orders(), ReadOperations.NAVIGATION_ORDERS,
                from -> ReadOperations.joinFetch(fixture, from));
        put(result, "manyToManyAndElementCollection", d.products(), ReadOperations.PRODUCTS,
                from -> ReadOperations.manyToManyAndElementCollection(fixture, from));
        put(result, "inheritanceSingleTableQuery", d.singleTablePersons(), ReadOperations.PERSONS,
                from -> ReadOperations.inheritanceSingleTableQuery(fixture, from));
        put(result, "inheritanceJoinedQuery", d.joinedPersons(), ReadOperations.PERSONS,
                from -> ReadOperations.inheritanceJoinedQuery(fixture, from));
        put(result, "criteriaQuery", ReadOperations.CRITERIA_DAYS, 1, day -> ReadOperations.criteriaQuery(fixture,
                DataGenerator.CITIES.get((int) (day % DataGenerator.CITIES.size())),
                ReadOperations.CRITERIA_EPOCH.plusDays(day - 1)));
        put(result, "pagination", ReadOperations.MAX_PAGES, 1, page -> ReadOperations.pagination(fixture,
                (int) page - 1));
        put(result, "dtoProjection", DataGenerator.ORDER_DAYS, ReadOperations.PROJECTION_DAYS,
                day -> ReadOperations.dtoProjection(fixture, date(day)));
        put(result, "aggregationGroupBy", DataGenerator.ORDER_DAYS, ReadOperations.AGGREGATION_DAYS,
                day -> ReadOperations.aggregationGroupBy(fixture, date(day)));
        put(result, "resultStream", d.orders(), streamRows,
                from -> ReadOperations.resultStream(fixture, from, streamRows));
        return result;
    }

    /** Logs the checksums of {@link #readChecksums}. */
    static void logReadChecksums(Fixture fixture) {
        readChecksums(fixture).forEach((operation, hex) -> fixture.env().log("verify " + operation + "=" + hex));
    }

    private static LocalDate date(long day) {
        return DataGenerator.ORDER_EPOCH.plusDays(day - 1);
    }

    /**
     * Runs {@code operation} for {@value #SAMPLES} window starts spread over {@code [1, max - size + 1]} and stores
     * the combined checksum.
     */
    private static void put(Map<String, String> result, String name, long max, int size,
                            LongUnaryOperator operation) {
        long last = Math.max(1, max - size + 1);
        long hash = 17;
        for (int i = 0; i < SAMPLES; i++) {
            long input = 1 + (last - 1) * i / (SAMPLES - 1);
            hash = hash * 1_000_003L + operation.applyAsLong(input);
        }
        result.put(name, String.format(Locale.ROOT, "%016x", hash));
    }
}
