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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal SQL script runner: strips {@code --} line comments and splits on {@code ;}. The DDL scripts contain no
 * string literals or procedural blocks, so nothing more is needed.
 */
public final class SqlScript {

    private SqlScript() {
    }

    public static List<String> statements(String script) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\\R")) {
            int comment = line.indexOf("--");
            cleaned.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        List<String> result = new ArrayList<>();
        for (String statement : cleaned.toString().split(";")) {
            String trimmed = statement.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public static String load(String resource) {
        try (InputStream in = SqlScript.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Executes the schema script of {@code database} and commits. */
    public static void createSchema(Connection connection, Database database) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements(load(database.ddlResource()))) {
                statement.execute(sql);
            }
        }
        if (!connection.getAutoCommit()) {
            connection.commit();
        }
    }
}
