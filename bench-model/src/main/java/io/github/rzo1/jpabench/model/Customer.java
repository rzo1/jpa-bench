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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * A customer with an embedded address and the (inverse, lazy) list of its orders.
 */
@Entity
@Table(name = "CUSTOMER")
public class Customer {

    @Id
    @Column(name = "ID")
    private long id;

    @Column(name = "FIRST_NAME", length = 60, nullable = false)
    private String firstName;

    @Column(name = "LAST_NAME", length = 60, nullable = false)
    private String lastName;

    @Column(name = "EMAIL", length = 120, nullable = false, unique = true)
    private String email;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDate createdAt;

    @Embedded
    private Address address;

    @OneToMany(mappedBy = "customer")
    private List<PurchaseOrder> orders = new ArrayList<>();

    protected Customer() {
    }

    public Customer(long id, String firstName, String lastName, String email, LocalDate createdAt, Address address) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.createdAt = createdAt;
        this.address = address;
    }

    public long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDate getCreatedAt() {
        return createdAt;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public List<PurchaseOrder> getOrders() {
        return orders;
    }
}
