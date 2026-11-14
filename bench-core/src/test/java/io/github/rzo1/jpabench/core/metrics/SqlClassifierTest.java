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
package io.github.rzo1.jpabench.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.rzo1.jpabench.core.metrics.SqlClassifier.Kind;

class SqlClassifierTest {

    @Test
    void classifiesByLeadingKeyword() {
        assertEquals(Kind.SELECT, SqlClassifier.kind("select c1_0.ID from CUSTOMER c1_0"));
        assertEquals(Kind.SELECT, SqlClassifier.kind("  /* comment */ (SELECT 1)"));
        assertEquals(Kind.SELECT, SqlClassifier.kind("-- note\nWITH x AS (SELECT 1) SELECT * FROM x"));
        assertEquals(Kind.INSERT, SqlClassifier.kind("INSERT INTO ORDERS VALUES (?)"));
        assertEquals(Kind.UPDATE, SqlClassifier.kind("update ORDERS set STATUS=?"));
        assertEquals(Kind.DELETE, SqlClassifier.kind("DELETE FROM ORDERS"));
        assertEquals(Kind.OTHER, SqlClassifier.kind("CALL foo()"));
        assertEquals(Kind.OTHER, SqlClassifier.kind(""));
    }

    @Test
    void countsJoinKeywords() {
        assertEquals(0, SqlClassifier.joins("SELECT JOINED_AT FROM T"));
        assertEquals(2, SqlClassifier.joins("SELECT * FROM A a LEFT OUTER JOIN B b ON 1=1 join C c ON 1=1"));
    }
}
