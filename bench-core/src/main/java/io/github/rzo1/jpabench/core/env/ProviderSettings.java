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
package io.github.rzo1.jpabench.core.env;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rzo1.jpabench.data.Database;

/**
 * Resolved settings of a run: {@value #COMMON} (bench-core) overlaid with {@value #PROVIDER} (the runner).
 *
 * <ul>
 *   <li>{@code bench.*} keys configure the harness and are never passed to the provider.</li>
 *   <li>{@code db.<id>.<key>} applies {@code <key>} only when running against database {@code <id>}.</li>
 *   <li>{@code ${key}} in a value is replaced with the value of {@code key}.</li>
 * </ul>
 */
public final class ProviderSettings {

    public static final String COMMON = "bench-common.properties";
    public static final String PROVIDER = "bench-provider.properties";
    public static final String BUILD = "bench-build.properties";

    private static final String HARNESS_PREFIX = "bench.";
    private static final String DB_PREFIX = "db.";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private final Map<String, String> harness;
    private final Map<String, String> provider;

    private ProviderSettings(Map<String, String> harness, Map<String, String> provider) {
        this.harness = harness;
        this.provider = provider;
    }

    /** Loads and resolves the settings for {@code database}. */
    public static ProviderSettings load(Database database) {
        Map<String, String> merged = new TreeMap<>();
        merged.putAll(read(COMMON, true));
        merged.putAll(read(BUILD, false));
        merged.putAll(read(PROVIDER, true));

        Map<String, String> effective = new TreeMap<>();
        String ownPrefix = DB_PREFIX + database.id() + ".";
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (!e.getKey().startsWith(DB_PREFIX)) {
                effective.put(e.getKey(), e.getValue());
            }
        }
        // database specific keys win over the generic ones
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (e.getKey().startsWith(ownPrefix)) {
                effective.put(e.getKey().substring(ownPrefix.length()), e.getValue());
            }
        }

        Map<String, String> harness = new TreeMap<>();
        Map<String, String> provider = new TreeMap<>();
        for (Map.Entry<String, String> e : effective.entrySet()) {
            String value = resolve(e.getValue(), effective, 0);
            if (e.getKey().startsWith(HARNESS_PREFIX)) {
                harness.put(e.getKey(), value);
            } else {
                provider.put(e.getKey(), value);
            }
        }
        return new ProviderSettings(Map.copyOf(harness), Map.copyOf(provider));
    }

    /** Settings passed to {@code Persistence.createEntityManagerFactory}, sorted by key. */
    public Map<String, String> providerProperties() {
        return new TreeMap<>(provider);
    }

    /** All {@code bench.*} settings, sorted by key. */
    public Map<String, String> harnessProperties() {
        return new TreeMap<>(harness);
    }

    public String harness(String key) {
        String value = harness.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing setting " + key + " (expected in " + COMMON + " or " + PROVIDER
                    + ")");
        }
        return value;
    }

    public String harness(String key, String defaultValue) {
        return harness.getOrDefault(key, defaultValue);
    }

    public int poolSize() {
        return Integer.parseInt(harness("bench.pool.size"));
    }

    public List<String> dataSourceKeys() {
        return Arrays.stream(harness("bench.datasource.keys").split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String providerName() {
        return harness("bench.provider.name", "unknown");
    }

    public String providerVersion() {
        return harness("bench.provider.version", "unknown");
    }

    private static String resolve(String value, Map<String, String> all, int depth) {
        if (depth > 10) {
            throw new IllegalStateException("Placeholder cycle in " + value);
        }
        Matcher m = PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = all.get(m.group(1));
            if (replacement == null) {
                throw new IllegalStateException("Unknown placeholder ${" + m.group(1) + "}");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(resolve(replacement, all, depth + 1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, String> read(String resource, boolean required) {
        Properties properties = new Properties();
        try (InputStream in = ProviderSettings.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                if (required) {
                    throw new IllegalStateException("Missing classpath resource " + resource
                            + " - every runner must provide it");
                }
                return Map.of();
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> result = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            result.put(key, properties.getProperty(key).strip());
        }
        return result;
    }
}
