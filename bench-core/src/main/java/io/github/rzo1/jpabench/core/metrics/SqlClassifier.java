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

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies SQL text by its leading keyword and counts JOIN keywords. Deliberately simple and identical for every
 * provider: it inspects the SQL as sent to the driver.
 */
public final class SqlClassifier {

    /** Statement kinds reported as separate counters. */
    public enum Kind {
        SELECT, INSERT, UPDATE, DELETE, OTHER
    }

    private static final Pattern JOIN = Pattern.compile("\\bjoin\\b", Pattern.CASE_INSENSITIVE);

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

    /** Number of {@code JOIN} keywords (inner, outer, cross; implicit theta joins are not counted). */
    public static int joins(String sql) {
        Matcher m = JOIN.matcher(sql);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
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
