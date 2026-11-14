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

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Postal address embedded into {@link Customer}.
 */
@Embeddable
public class Address {

    @Column(name = "STREET", length = 120)
    private String street;

    @Column(name = "CITY", length = 80)
    private String city;

    @Column(name = "ZIP", length = 16)
    private String zip;

    @Column(name = "COUNTRY", length = 2)
    private String country;

    public Address() {
    }

    public Address(String street, String city, String zip, String country) {
        this.street = street;
        this.city = city;
        this.zip = zip;
        this.country = country;
    }

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getZip() {
        return zip;
    }

    public void setZip(String zip) {
        this.zip = zip;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }
}
