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

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 64-bit FNV-1a checksum over the canonical text form (the record {@code toString()}) of every generated row.
 * Two datasets with the same checksum contain exactly the same rows in the same order.
 */
public final class Checksum {

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private long hash = OFFSET_BASIS;
    private long records;

    public void update(Record row) {
        byte[] bytes = row.toString().getBytes(StandardCharsets.UTF_8);
        long h = hash;
        for (byte b : bytes) {
            h ^= b & 0xff;
            h *= PRIME;
        }
        // row separator
        h ^= '\n';
        h *= PRIME;
        hash = h;
        records++;
    }

    public long records() {
        return records;
    }

    public String hex() {
        return String.format(Locale.ROOT, "%016x", hash);
    }
}
