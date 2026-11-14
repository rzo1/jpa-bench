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
import jakarta.persistence.Table;

/**
 * Employee in the JOINED person hierarchy.
 */
@Entity
@Table(name = "JN_EMPLOYEE")
@DiscriminatorValue("EMP")
public class JnEmployee extends JnPerson {

    @Column(name = "DEPARTMENT", length = 40)
    private String department;

    @Column(name = "SALARY", precision = 12, scale = 2)
    private BigDecimal salary;

    protected JnEmployee() {
    }

    public JnEmployee(long id, String name, String email, String department, BigDecimal salary) {
        super(id, name, email);
        this.department = department;
        this.salary = salary;
    }

    public String getDepartment() {
        return department;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public void setSalary(BigDecimal salary) {
        this.salary = salary;
    }
}
