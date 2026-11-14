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
package io.github.rzo1.jpabench.core;

import java.util.Map;
import java.util.TreeMap;

import io.github.rzo1.jpabench.core.env.ProviderSettings;
import io.github.rzo1.jpabench.data.DataGenerator;
import io.github.rzo1.jpabench.data.Database;

/**
 * Prints the provider, library versions and the resolved settings of this runner as JSON. Used by run.sh to
 * write metadata.json: {@code java -cp benchmarks.jar io.github.rzo1.jpabench.core.ProviderInfo [db]}.
 */
public final class ProviderInfo {

    private ProviderInfo() {
    }

    public static void main(String[] args) {
        Database database = Database.of(args.length > 0 ? args[0] : "h2");
        ProviderSettings settings = ProviderSettings.load(database);
        Map<String, Object> json = new TreeMap<>();
        json.put("database", database.id());
        json.put("seed", DataGenerator.DEFAULT_SEED);
        json.put("harness", settings.harnessProperties());
        json.put("providerProperties", settings.providerProperties());
        System.out.println(toJson(json, ""));
    }

    static String toJson(Object value, String indent) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{\n");
            String inner = indent + "  ";
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append(inner).append(quote(String.valueOf(e.getKey()))).append(": ")
                        .append(toJson(e.getValue(), inner));
                if (++i < map.size()) {
                    sb.append(',');
                }
                sb.append('\n');
            }
            return sb.append(indent).append('}').toString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return quote(String.valueOf(value));
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
