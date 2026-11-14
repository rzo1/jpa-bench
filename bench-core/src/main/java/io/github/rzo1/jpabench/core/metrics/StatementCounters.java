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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;

/**
 * Exposes the {@link StatementMetrics} of one iteration as JMH secondary metrics. Every benchmark method takes this
 * state and calls {@link #op()} once per operation.
 *
 * <p>All public accessors except {@link #ops()} and {@link #selectMaxChars()} are <b>per operation</b>
 * (iteration delta divided by the number of operations of the iteration). JMH reports {@code EVENTS} counters as
 * the <em>sum</em> over all measurement iterations and forks, so the per-operation value is the mean of the
 * {@code rawData} of the metric in the JMH JSON (which bench-viz uses), or the score divided by
 * iterations x forks.</p>
 *
 * <p>The distinct SQL texts of the first iteration of every trial are written to
 * {@code $JPABENCH_SQL_DIR/<benchmark>-<mode>-<params>.sql} when that environment variable is set.</p>
 */
@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
public class StatementCounters {
    // JMH turns every public field and every public non-void method of this class into a counter:
    // constants and helpers must not be public.

    private final StatementMetrics metrics = StatementMetrics.INSTANCE;
    private StatementMetrics.Snapshot base;
    private long operations;
    private int iteration;
    private String sqlFileName;

    @Setup(Level.Trial)
    public void beginTrial(BenchmarkParams params) {
        Map<String, String> sorted = new TreeMap<>();
        for (String key : params.getParamsKeys()) {
            sorted.put(key, params.getParam(key));
        }
        StringBuilder name = new StringBuilder(simpleName(params.getBenchmark()))
                .append('-').append(params.getMode().shortLabel());
        sorted.forEach((k, v) -> name.append('-').append(k).append('=').append(v));
        sqlFileName = name.toString().replaceAll("[^A-Za-z0-9=._-]", "_") + ".sql";
        iteration = 0;
    }

    @Setup(Level.Iteration)
    public void beginIteration() {
        metrics.capture(iteration == 0);
        iteration++;
        metrics.resetMax();
        base = metrics.snapshot();
        operations = 0;
    }

    @TearDown(Level.Trial)
    public void endTrial() {
        metrics.capture(false);
        Collection<String> sql = metrics.drainCaptured();
        String dir = System.getenv(StatementMetrics.ENV_SQL_DIR);
        if (dir == null || dir.isBlank() || sql.isEmpty()) {
            return;
        }
        try {
            Path target = Path.of(dir);
            Files.createDirectories(target);
            StringBuilder text = new StringBuilder();
            for (String statement : sql) {
                text.append(statement.strip()).append(";\n\n");
            }
            Files.writeString(target.resolve(sqlFileName), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Marks one completed benchmark operation. Not a counter (void). */
    public void op() {
        operations++;
    }

    private StatementMetrics.Snapshot delta() {
        return metrics.snapshot().minus(base);
    }

    private double perOp(long value) {
        return operations == 0 ? 0 : (double) value / operations;
    }

    /** Operations of this iteration (raw count). */
    public long ops() {
        return operations;
    }

    public double stmtSelect() {
        return perOp(delta().select());
    }

    public double stmtInsert() {
        return perOp(delta().insert());
    }

    public double stmtUpdate() {
        return perOp(delta().update());
    }

    public double stmtDelete() {
        return perOp(delta().delete());
    }

    public double stmtOther() {
        return perOp(delta().other());
    }

    public double stmtTotal() {
        return perOp(delta().total());
    }

    public double jdbcExecutions() {
        return perOp(delta().executions());
    }

    public double jdbcBatches() {
        return perOp(delta().batches());
    }

    public double jdbcBatchedRows() {
        return perOp(delta().batchedRows());
    }

    public double sqlChars() {
        return perOp(delta().sqlChars());
    }

    public double selectJoins() {
        return perOp(delta().selectJoins());
    }

    /** Longest SELECT of this iteration in characters (not per operation). */
    public long selectMaxChars() {
        return metrics.selectMaxChars();
    }

    private static String simpleName(String benchmark) {
        int dot = benchmark.lastIndexOf('.');
        int classDot = benchmark.lastIndexOf('.', dot - 1);
        return classDot >= 0 ? benchmark.substring(classDot + 1) : benchmark;
    }
}
