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
package io.github.rzo1.jpabench.model;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;

/**
 * Contractor in the SINGLE_TABLE person hierarchy.
 */
@Entity
@DiscriminatorValue("CON")
public class StContractor extends StPerson {

    @Column(name = "AGENCY", length = 60)
    private String agency;

    @Column(name = "HOURLY_RATE", precision = 12, scale = 2)
    private BigDecimal hourlyRate;

    protected StContractor() {
    }

    public StContractor(long id, String name, String email, String agency, BigDecimal hourlyRate) {
        super(id, name, email);
        this.agency = agency;
        this.hourlyRate = hourlyRate;
    }

    public String getAgency() {
        return agency;
    }

    public BigDecimal getHourlyRate() {
        return hourlyRate;
    }
}
