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

/**
 * Dataset sizes. All entity counts scale with the same factor.
 */
public enum DatasetSize {
    S(1),
    M(10),
    L(50);

    /** Number of categories, independent of the size. */
    public static final int CATEGORIES = 25;

    private final int scale;

    DatasetSize(int scale) {
        this.scale = scale;
    }

    public int scale() {
        return scale;
    }

    public int products() {
        return 500 * scale;
    }

    public int customers() {
        return 1_000 * scale;
    }

    public int orders() {
        return 5 * customers();
    }

    /** Persons per inheritance hierarchy (SINGLE_TABLE and JOINED each get this many). */
    public int persons() {
        return 1_000 * scale;
    }
}
