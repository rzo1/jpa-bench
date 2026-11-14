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
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A line of a {@link PurchaseOrder} referencing a {@link Product}.
 */
@Entity
@Table(name = "ORDER_LINE")
public class OrderLine {

    @Id
    @Column(name = "ID")
    private long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ORDER_ID", nullable = false)
    private PurchaseOrder order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "PRODUCT_ID", nullable = false)
    private Product product;

    @Column(name = "LINE_NO", nullable = false)
    private int lineNo;

    @Column(name = "QUANTITY", nullable = false)
    private int quantity;

    @Column(name = "UNIT_PRICE", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    protected OrderLine() {
    }

    public OrderLine(long id, Product product, int lineNo, int quantity, BigDecimal unitPrice) {
        this.id = id;
        this.product = product;
        this.lineNo = lineNo;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public long getId() {
        return id;
    }

    public PurchaseOrder getOrder() {
        return order;
    }

    void setOrder(PurchaseOrder order) {
        this.order = order;
    }

    public Product getProduct() {
        return product;
    }

    public int getLineNo() {
        return lineNo;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
