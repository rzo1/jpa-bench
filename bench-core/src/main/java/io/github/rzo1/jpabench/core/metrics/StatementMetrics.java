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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.StatementType;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;

/**
 * Counts every statement a provider sends through the metered DataSource. Registered as a datasource-proxy
 * listener; the JDBC work done by the harness itself (loading, resetting) uses the raw pool and is not counted.
 *
 * <p>Counting rules (identical for every provider):</p>
 * <ul>
 *   <li>Statements by kind count logical statements: a JDBC batch of a prepared statement with {@code n}
 *       parameter sets counts {@code n}.</li>
 *   <li>{@code executions} counts round trips: one per {@code execute*} call, one per {@code executeBatch}.</li>
 *   <li>{@code batches} counts {@code executeBatch} calls, {@code batchedRows} the parameter sets (or statements)
 *       they contained.</li>
 *   <li>{@code sqlChars} sums the SQL text length of every execution; a batch counts each distinct text once.</li>
 *   <li>{@code selectJoins} sums the joins of all executed SELECTs ({@link SqlClassifier#joins}: JOIN keywords
 *       plus theta joins), {@code selectMaxChars} is the longest SELECT since the last {@link #resetMax()}.</li>
 * </ul>
 */
public final class StatementMetrics implements QueryExecutionListener {

    /** Environment variable naming the directory for the distinct SQL sidecar files. */
    public static final String ENV_SQL_DIR = "JPABENCH_SQL_DIR";

    /** The single instance; benchmarks are single threaded per fork. */
    public static final StatementMetrics INSTANCE = new StatementMetrics();

    /** Immutable view of the counters at one point in time. */
    public record Snapshot(long select, long insert, long update, long delete, long other, long executions,
                           long batches, long batchedRows, long sqlChars, long selectJoins) {

        public long total() {
            return select + insert + update + delete + other;
        }

        public Snapshot minus(Snapshot base) {
            return new Snapshot(select - base.select, insert - base.insert, update - base.update,
                    delete - base.delete, other - base.other, executions - base.executions,
                    batches - base.batches, batchedRows - base.batchedRows, sqlChars - base.sqlChars,
                    selectJoins - base.selectJoins);
        }
    }

    private long select;
    private long insert;
    private long update;
    private long delete;
    private long other;
    private long executions;
    private long batches;
    private long batchedRows;
    private long sqlChars;
    private long selectJoins;
    private long selectMaxChars;

    private boolean capturing;
    private final Set<String> captured = new LinkedHashSet<>();

    private StatementMetrics() {
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        // counted after execution
    }

    @Override
    public synchronized void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        executions++;
        boolean batch = execInfo.isBatch();
        if (batch) {
            batches++;
        }
        for (QueryInfo query : queryInfoList) {
            String sql = query.getQuery();
            long count = 1;
            if (batch) {
                count = execInfo.getStatementType() == StatementType.STATEMENT
                        ? 1
                        : Math.max(1, query.getParametersList().size());
                batchedRows += count;
            }
            sqlChars += sql.length();
            switch (SqlClassifier.kind(sql)) {
                case SELECT -> {
                    select += count;
                    selectJoins += count * SqlClassifier.joins(sql);
                    selectMaxChars = Math.max(selectMaxChars, sql.length());
                }
                case INSERT -> insert += count;
                case UPDATE -> update += count;
                case DELETE -> delete += count;
                case OTHER -> other += count;
            }
            if (capturing) {
                captured.add(sql);
            }
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(select, insert, update, delete, other, executions, batches, batchedRows, sqlChars,
                selectJoins);
    }

    /** Longest SELECT text since the last {@link #resetMax()}. */
    public synchronized long selectMaxChars() {
        return selectMaxChars;
    }

    public synchronized void resetMax() {
        selectMaxChars = 0;
    }

    /** Starts or stops recording the distinct SQL texts. */
    public synchronized void capture(boolean enabled) {
        capturing = enabled;
    }

    /** Returns and clears the distinct SQL texts recorded so far. */
    public synchronized Collection<String> drainCaptured() {
        List<String> result = List.copyOf(captured);
        captured.clear();
        return result;
    }
}
