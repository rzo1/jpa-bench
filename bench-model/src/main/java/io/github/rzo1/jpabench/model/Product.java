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
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

/**
 * A product with a many-to-many association to {@link Category} and a collection of tags.
 */
@Entity
@Table(name = "PRODUCT")
public class Product {

    @Id
    @Column(name = "ID")
    private long id;

    @Column(name = "SKU", length = 32, nullable = false, unique = true)
    private String sku;

    @Column(name = "NAME", length = 120, nullable = false)
    private String name;

    @Column(name = "PRICE", precision = 12, scale = 2, nullable = false)
    private BigDecimal price;

    @ManyToMany
    @JoinTable(name = "PRODUCT_CATEGORY",
            joinColumns = @JoinColumn(name = "PRODUCT_ID"),
            inverseJoinColumns = @JoinColumn(name = "CATEGORY_ID"))
    private Set<Category> categories = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "PRODUCT_TAG", joinColumns = @JoinColumn(name = "PRODUCT_ID"))
    @Column(name = "TAG", length = 40, nullable = false)
    private Set<String> tags = new HashSet<>();

    protected Product() {
    }

    public Product(long id, String sku, String name, BigDecimal price) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.price = price;
    }

    public long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Set<Category> getCategories() {
        return categories;
    }

    public Set<String> getTags() {
        return tags;
    }
}
