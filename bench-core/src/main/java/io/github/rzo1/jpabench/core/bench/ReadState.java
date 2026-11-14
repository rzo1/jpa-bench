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
package io.github.rzo1.jpabench.core.bench;

import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * State of the read benchmarks. Dataset and EntityManagerFactory are created once per trial; the read checksums
 * are logged before the first iteration ({@link Verification}).
 */
@State(Scope.Benchmark)
public class ReadState {

    /** Database id: h2 or postgresql. */
    @Param({"h2"})
    public String db;

    /** Dataset size: S, M or L. */
    @Param({"S"})
    public String dataset;

    Fixture fixture;

    @Setup(Level.Trial)
    public void setUp() {
        fixture = Fixture.withData(db, dataset);
        Verification.logReadChecksums(fixture);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        fixture.close();
    }
}
