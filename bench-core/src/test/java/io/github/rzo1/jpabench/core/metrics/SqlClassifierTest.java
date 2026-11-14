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
        assertEquals(0, SqlClassifier.joins("select c1_0.ID, c1_0.EMAIL from CUSTOMER c1_0 where c1_0.ID=?"));
    }

    @Test
    void countsThetaJoinsLikeExplicitJoins() {
        // EclipseLink: join fetch of customer and lines
        assertEquals(2, SqlClassifier.joins("SELECT DISTINCT t1.ID, t0.ID, t2.ID FROM CUSTOMER t0, ORDER_LINE t2,"
                + " ORDERS t1 WHERE ((t1.ID BETWEEN ? AND ?) AND ((t0.ID = t1.CUSTOMER_ID) AND"
                + " (t2.ORDER_ID = t1.ID))) ORDER BY t1.ID"));
        // Hibernate: the same with explicit joins
        assertEquals(2, SqlClassifier.joins("select distinct o1_0.ID from ORDERS o1_0 join CUSTOMER c1_0"
                + " on c1_0.ID=o1_0.CUSTOMER_ID join ORDER_LINE l1_0 on o1_0.ID=l1_0.ORDER_ID"
                + " where o1_0.ID between ? and ? order by o1_0.ID"));
        // mixed: one theta join and one explicit join
        assertEquals(2, SqlClassifier.joins("SELECT * FROM A t0 LEFT OUTER JOIN B t1 ON (t1.X = t0.X), C t2"
                + " WHERE t2.Y = t0.Y"));
    }

    @Test
    void countsJoinsOfSubqueriesSeparately() {
        assertEquals(1, SqlClassifier.joins("SELECT t0.ID FROM A t0 WHERE EXISTS (SELECT 1 FROM B t1, C t2"
                + " WHERE t1.A_ID = t0.ID AND t2.B_ID = t1.ID) AND t0.X IN (1, 2, 3)"));
        assertEquals(1, SqlClassifier.joins("SELECT t0.ID, (SELECT COUNT(*) FROM B t1 WHERE t1.A = t0.ID),"
                + " t0.NAME FROM A t0, D t3 WHERE t3.ID = t0.D_ID"));
    }

    @Test
    void ignoresCommasOutsideTableLists() {
        assertEquals(0, SqlClassifier.joins("SELECT a, b, COALESCE(c, d) FROM T WHERE x IN (?, ?) ORDER BY a, b"));
        assertEquals(0, SqlClassifier.joins("SELECT EXTRACT(YEAR FROM d), 'FROM a, b' FROM T LIMIT ? OFFSET ?"));
        assertEquals(0, SqlClassifier.joins("SELECT \"from\", x FROM T /* FROM a, b */ WHERE y = 1"));
        assertEquals(0, SqlClassifier.joins("SELECT * FROM T t0 FETCH FIRST 20 ROWS ONLY"));
        assertEquals(0, SqlClassifier.joins("UPDATE ORDERS SET STATUS = ?, TOTAL = ? WHERE ID = ?"));
    }
}
