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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * An order, mapped to table {@code ORDERS}. Not named {@code Order}: ORDER is an SQL keyword and the name would
 * clash with {@code jakarta.persistence.criteria.Order}.
 */
@Entity(name = "PurchaseOrder")
@Table(name = "ORDERS")
public class PurchaseOrder {

    @Id
    @Column(name = "ID")
    private long id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private int version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "CUSTOMER_ID", nullable = false)
    private Customer customer;

    @Column(name = "ORDER_DATE", nullable = false)
    private LocalDate orderDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 16, nullable = false)
    private OrderStatus status;

    @Column(name = "TOTAL", precision = 12, scale = 2, nullable = false)
    private BigDecimal total;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<OrderLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public PurchaseOrder(long id, Customer customer, LocalDate orderDate, OrderStatus status, BigDecimal total) {
        this.id = id;
        this.customer = customer;
        this.orderDate = orderDate;
        this.status = status;
        this.total = total;
    }

    /**
     * Adds a line and maintains both sides of the association.
     */
    public void addLine(OrderLine line) {
        lines.add(line);
        line.setOrder(this);
    }

    public long getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public LocalDate getOrderDate() {
        return orderDate;
    }

    public void setOrderDate(LocalDate orderDate) {
        this.orderDate = orderDate;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public List<OrderLine> getLines() {
        return lines;
    }
}
