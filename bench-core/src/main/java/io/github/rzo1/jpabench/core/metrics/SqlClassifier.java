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

import java.util.Arrays;
import java.util.Locale;

/**
 * Classifies SQL text by its leading keyword and counts joins. Deliberately simple and identical for every
 * provider: it inspects the SQL as sent to the driver. Both methods run once per executed statement inside the
 * measured region (when metering is on), so they scan the text once without regular expressions.
 */
public final class SqlClassifier {

    /** Statement kinds reported as separate counters. */
    public enum Kind {
        SELECT, INSERT, UPDATE, DELETE, OTHER
    }

    /** Keywords that end the table list of a FROM clause at the same parenthesis depth. */
    private static final String[] FROM_TERMINATORS = {
        "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "INTERSECT", "EXCEPT", "MINUS", "LIMIT", "OFFSET", "FETCH",
        "FOR", "WINDOW", "RETURNING", "SET", "VALUES"
    };

    private SqlClassifier() {
    }

    public static Kind kind(String sql) {
        int i = skipNoise(sql, 0);
        int end = i;
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        String keyword = sql.substring(i, end).toUpperCase(Locale.ROOT);
        return switch (keyword) {
            case "SELECT", "WITH", "VALUES" -> Kind.SELECT;
            case "INSERT" -> Kind.INSERT;
            case "UPDATE" -> Kind.UPDATE;
            case "DELETE" -> Kind.DELETE;
            default -> Kind.OTHER;
        };
    }

    /**
     * Number of joins: every {@code JOIN} keyword (inner, outer, cross) plus every additional table of a
     * comma-separated FROM list (theta join, as EclipseLink writes {@code FROM A t0, B t1 WHERE ...}). In other
     * words, the tables joined to the first table of each query block, subqueries included. String literals,
     * quoted identifiers and comments are skipped.
     */
    public static int joins(String sql) {
        int length = sql.length();
        // inFrom[d]: a FROM table list is open at parenthesis depth d
        boolean[] inFrom = new boolean[8];
        int depth = 0;
        int joins = 0;
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int close = sql.indexOf(c, i + 1);
                i = close < 0 ? length : close + 1;
            } else if (c == '-' && sql.startsWith("--", i)) {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? length : eol + 1;
            } else if (c == '/' && sql.startsWith("/*", i)) {
                int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? length : close + 2;
            } else if (c == '(') {
                depth++;
                if (depth == inFrom.length) {
                    inFrom = Arrays.copyOf(inFrom, depth * 2);
                }
                inFrom[depth] = false;
                i++;
            } else if (c == ')') {
                inFrom[depth] = false;
                depth = Math.max(0, depth - 1);
                i++;
            } else if (c == ',') {
                if (inFrom[depth]) {
                    joins++;
                }
                i++;
            } else if (isWordChar(c)) {
                int end = i + 1;
                while (end < length && isWordChar(sql.charAt(end))) {
                    end++;
                }
                if (isWord(sql, i, end, "JOIN")) {
                    joins++;
                } else if (isWord(sql, i, end, "FROM")) {
                    inFrom[depth] = true;
                } else if (inFrom[depth] && isTerminator(sql, i, end)) {
                    inFrom[depth] = false;
                }
                i = end;
            } else {
                i++;
            }
        }
        return joins;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isWord(String sql, int start, int end, String word) {
        return end - start == word.length() && sql.regionMatches(true, start, word, 0, word.length());
    }

    private static boolean isTerminator(String sql, int start, int end) {
        for (String keyword : FROM_TERMINATORS) {
            if (isWord(sql, start, end, keyword)) {
                return true;
            }
        }
        return false;
    }

    /** Skips whitespace, opening parentheses and SQL comments. */
    private static int skipNoise(String sql, int start) {
        int i = start;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '(') {
                i++;
            } else if (sql.startsWith("/*", i)) {
                int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? sql.length() : close + 2;
            } else if (sql.startsWith("--", i)) {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? sql.length() : eol + 1;
            } else {
                break;
            }
        }
        return i;
    }
}
