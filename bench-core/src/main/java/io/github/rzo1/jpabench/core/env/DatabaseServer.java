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

import java.util.Map;

import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.rzo1.jpabench.data.Database;

/**
 * A running database a benchmark connects to.
 */
public sealed interface DatabaseServer extends AutoCloseable {

    /** Environment variable selecting the PostgreSQL image (default: {@code bench.postgres.image}, pinned by digest). */
    String ENV_PG_IMAGE = "JPABENCH_PG_IMAGE";
    /** Environment variable with the CPU limit of the PostgreSQL container (default 2). */
    String ENV_PG_CPUS = "JPABENCH_PG_CPUS";
    /** Environment variable with the memory limit of the PostgreSQL container in MiB (default 2048). */
    String ENV_PG_MEMORY_MB = "JPABENCH_PG_MEMORY_MB";
    /** Use an already running PostgreSQL instead of Testcontainers (JDBC URL; user/password below). */
    String ENV_PG_URL = "JPABENCH_PG_URL";
    String ENV_PG_USER = "JPABENCH_PG_USER";
    String ENV_PG_PASSWORD = "JPABENCH_PG_PASSWORD";

    Database database();

    String jdbcUrl();

    String user();

    String password();

    /** Human readable description for logs and metadata. */
    String description();

    @Override
    void close();

    /** Starts {@code database}; settings provide the defaults ({@code bench.postgres.image}). */
    static DatabaseServer start(Database database, ProviderSettings settings) {
        return switch (database) {
            case H2 -> new H2();
            case POSTGRESQL -> PostgreSql.start(settings.harness(PostgreSql.IMAGE_SETTING));
        };
    }

    /** In-memory H2 inside the benchmark JVM. */
    record H2() implements DatabaseServer {

        @Override
        public Database database() {
            return Database.H2;
        }

        @Override
        public String jdbcUrl() {
            return "jdbc:h2:mem:jpabench;DB_CLOSE_DELAY=-1";
        }

        @Override
        public String user() {
            return "sa";
        }

        @Override
        public String password() {
            return "";
        }

        @Override
        public String description() {
            return "H2 in-memory (inside the benchmark JVM)";
        }

        @Override
        public void close() {
            // the in-memory database dies with the fork
        }
    }

    /**
     * PostgreSQL in a Testcontainers container with fixed CPU and memory limits, or an external server when
     * {@value #ENV_PG_URL} is set.
     */
    final class PostgreSql implements DatabaseServer {

        /** Setting (from bench-build.properties, i.e. the root pom) with the default image. */
        public static final String IMAGE_SETTING = "bench.postgres.image";
        public static final int DEFAULT_CPUS = 2;
        public static final int DEFAULT_MEMORY_MB = 2048;

        private final PostgreSQLContainer container;
        private final String url;
        private final String user;
        private final String password;
        private final String description;

        private PostgreSql(PostgreSQLContainer container, String url, String user, String password,
                           String description) {
            this.container = container;
            this.url = url;
            this.user = user;
            this.password = password;
            this.description = description;
        }

        static PostgreSql start(String defaultImage) {
            Map<String, String> env = System.getenv();
            String external = env.get(ENV_PG_URL);
            if (external != null && !external.isBlank()) {
                return new PostgreSql(null, external, env.getOrDefault(ENV_PG_USER, "bench"),
                        env.getOrDefault(ENV_PG_PASSWORD, "bench"), "external PostgreSQL " + external);
            }
            String image = env.getOrDefault(ENV_PG_IMAGE, defaultImage);
            int cpus = Integer.parseInt(env.getOrDefault(ENV_PG_CPUS, String.valueOf(DEFAULT_CPUS)));
            long memoryMb = Long.parseLong(env.getOrDefault(ENV_PG_MEMORY_MB, String.valueOf(DEFAULT_MEMORY_MB)));
            PostgreSQLContainer container = new PostgreSQLContainer(imageName(image))
                    .withDatabaseName("bench")
                    .withUsername("bench")
                    .withPassword("bench")
                    .withTmpFs(Map.of("/var/lib/postgresql", "rw"))
                    // fsync=off is the Testcontainers default (the data directory is a tmpfs anyway);
                    // autovacuum=off: no background vacuum/analyze during measurement, the harness runs ANALYZE
                    // after loading and VACUUM ANALYZE before every write iteration itself
                    .withCommand("postgres", "-c", "fsync=off", "-c", "autovacuum=off")
                    .withCreateContainerCmdModifier(cmd -> {
                        if (cmd.getHostConfig() != null) {
                            cmd.getHostConfig()
                                    .withNanoCPUs(cpus * 1_000_000_000L)
                                    .withMemory(memoryMb * 1024 * 1024)
                                    .withMemorySwap(memoryMb * 1024 * 1024);
                        }
                    })
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
            container.start();
            return new PostgreSql(container, container.getJdbcUrl(), container.getUsername(),
                    container.getPassword(),
                    "PostgreSQL " + image + " (Testcontainers, cpus=" + cpus + ", memory=" + memoryMb
                            + "MiB, tmpfs, fsync=off, autovacuum=off)");
        }

        /**
         * Docker accepts {@code name:tag@sha256:digest}, Testcontainers only {@code name:tag} or
         * {@code name@sha256:digest}: with a digest the tag is informational only and dropped here.
         */
        static DockerImageName imageName(String image) {
            int at = image.indexOf("@sha256:");
            String reference = image;
            if (at > 0) {
                String name = image.substring(0, at);
                int colon = name.lastIndexOf(':');
                if (colon > name.lastIndexOf('/')) {
                    name = name.substring(0, colon);
                }
                reference = name + image.substring(at);
            }
            return DockerImageName.parse(reference).asCompatibleSubstituteFor("postgres");
        }

        @Override
        public Database database() {
            return Database.POSTGRESQL;
        }

        @Override
        public String jdbcUrl() {
            return url;
        }

        @Override
        public String user() {
            return user;
        }

        @Override
        public String password() {
            return password;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public void close() {
            if (container != null) {
                container.stop();
            }
        }
    }
}
