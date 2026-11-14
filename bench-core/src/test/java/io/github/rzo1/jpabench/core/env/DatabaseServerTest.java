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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DatabaseServerTest {

    @Test
    void dropsTheTagOfAnImagePinnedByDigest() {
        String digest = "sha256:1090bc3a8ccfb0b55f78a494d76f8d603434f7e4553543d6e807bc7bd6bbd17f";
        assertEquals("postgres@" + digest,
                DatabaseServer.PostgreSql.imageName("postgres:18.1@" + digest).asCanonicalNameString()
                        .replace("docker.io/library/", "").replace("library/", ""));
        assertEquals("localhost:5000/pg@" + digest,
                DatabaseServer.PostgreSql.imageName("localhost:5000/pg:18@" + digest).asCanonicalNameString());
        assertEquals("postgres:18.1", DatabaseServer.PostgreSql.imageName("postgres:18.1").asCanonicalNameString()
                .replace("docker.io/library/", "").replace("library/", ""));
    }
}
