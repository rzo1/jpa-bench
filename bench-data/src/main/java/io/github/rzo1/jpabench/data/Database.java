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

import java.util.Locale;

/**
 * The databases a benchmark can run against. The {@link #id()} is the value of the JMH {@code db} parameter and
 * the prefix of database specific provider settings ({@code db.<id>.<key>=<value>}).
 */
public enum Database {
    H2("h2", "ddl/h2.sql"),
    POSTGRESQL("postgresql", "ddl/postgresql.sql");

    private final String id;
    private final String ddlResource;

    Database(String id, String ddlResource) {
        this.id = id;
        this.ddlResource = ddlResource;
    }

    public String id() {
        return id;
    }

    /** Classpath resource of the schema script. */
    public String ddlResource() {
        return ddlResource;
    }

    public static Database of(String id) {
        String normalized = id.toLowerCase(Locale.ROOT);
        for (Database database : values()) {
            if (database.id.equals(normalized)) {
                return database;
            }
        }
        throw new IllegalArgumentException("Unknown database '" + id + "', expected one of h2, postgresql");
    }
}
