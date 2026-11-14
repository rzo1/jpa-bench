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
package io.github.rzo1.jpabench.core.bench;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import io.github.rzo1.jpabench.core.metrics.StatementCounters;
import io.github.rzo1.jpabench.data.DataGenerator;
import io.github.rzo1.jpabench.data.DatasetInfo;
import io.github.rzo1.jpabench.model.Category;
import io.github.rzo1.jpabench.model.Customer;
import io.github.rzo1.jpabench.model.JnContractor;
import io.github.rzo1.jpabench.model.JnEmployee;
import io.github.rzo1.jpabench.model.JnPerson;
import io.github.rzo1.jpabench.model.OrderLine;
import io.github.rzo1.jpabench.model.OrderStatus;
import io.github.rzo1.jpabench.model.Product;
import io.github.rzo1.jpabench.model.PurchaseOrder;
import io.github.rzo1.jpabench.model.StContractor;
import io.github.rzo1.jpabench.model.StEmployee;
import io.github.rzo1.jpabench.model.StPerson;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/**
 * Read benchmarks. Every operation uses a fresh EntityManager and transaction, picks its input with a seeded
 * random generator and touches exactly the data the operation is supposed to load, folding it into a checksum
 * that is consumed by the {@link Blackhole}.
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 10)
@Measurement(iterations = 5, time = 10)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class ReadBenchmarks {

    /** Orders per lazy navigation / join fetch operation. */
    static final int NAVIGATION_ORDERS = 20;
    /** Products per many-to-many / element collection operation. */
    static final int PRODUCTS = 10;
    /** Persons per inheritance query. */
    static final int PERSONS = 50;
    /** Page size of the pagination benchmark; at most {@link #MAX_PAGES} different pages are read. */
    static final int PAGE_SIZE = 20;
    static final int MAX_PAGES = 50;
    /** Maximum rows of the criteria query and the DTO projection. */
    static final int MAX_ROWS = 50;
    /** Days of orders covered by the DTO projection and the aggregation. */
    static final int PROJECTION_DAYS = 7;
    static final int AGGREGATION_DAYS = 30;
    /** Orders streamed by the large result benchmark. */
    static final int STREAM_ORDERS = 2_000;

    private SplittableRandom random;

    @Setup(Level.Trial)
    public void seed() {
        random = new SplittableRandom(4711);
    }

    private long randomId(long max) {
        return 1 + random.nextLong(max);
    }

    /** First id of a window of {@code size} consecutive ids within {@code [1, max]}. */
    private long randomWindow(long max, int size) {
        return 1 + random.nextLong(Math.max(1, max - size + 1));
    }

    @Benchmark
    public void findById(ReadState state, StatementCounters counters, Blackhole bh) {
        long id = randomId(state.fixture.dataset().customers());
        bh.consume(state.fixture.inTransaction(em -> touch(em.find(Customer.class, id))));
        counters.op();
    }

    @Benchmark
    public void jpqlByIndexedAttribute(ReadState state, StatementCounters counters, Blackhole bh) {
        String email = DataGenerator.customerEmail(randomId(state.fixture.dataset().customers()));
        bh.consume(state.fixture.inTransaction(em -> touch(
                em.createQuery("select c from Customer c where c.email = :email", Customer.class)
                        .setParameter("email", email)
                        .getSingleResult())));
        counters.op();
    }

    /** Loads orders and navigates the lazy customer and lines associations one by one (N+1 selects). */
    @Benchmark
    public void lazyNavigationNPlusOne(ReadState state, StatementCounters counters, Blackhole bh) {
        long from = randomWindow(state.fixture.dataset().orders(), NAVIGATION_ORDERS);
        bh.consume(state.fixture.inTransaction(em -> touchNavigation(
                em.createQuery("select o from PurchaseOrder o where o.id between :from and :to order by o.id",
                                PurchaseOrder.class)
                        .setParameter("from", from)
                        .setParameter("to", from + NAVIGATION_ORDERS - 1)
                        .getResultList())));
        counters.op();
    }

    /** Loads the same data as {@link #lazyNavigationNPlusOne} with fetch joins in one query. */
    @Benchmark
    public void joinFetch(ReadState state, StatementCounters counters, Blackhole bh) {
        long from = randomWindow(state.fixture.dataset().orders(), NAVIGATION_ORDERS);
        bh.consume(state.fixture.inTransaction(em -> touchNavigation(
                em.createQuery("select distinct o from PurchaseOrder o join fetch o.customer join fetch o.lines"
                                + " where o.id between :from and :to order by o.id", PurchaseOrder.class)
                        .setParameter("from", from)
                        .setParameter("to", from + NAVIGATION_ORDERS - 1)
                        .getResultList())));
        counters.op();
    }

    @Benchmark
    public void manyToManyAndElementCollection(ReadState state, StatementCounters counters, Blackhole bh) {
        long from = randomWindow(state.fixture.dataset().products(), PRODUCTS);
        bh.consume(state.fixture.inTransaction(em -> {
            List<Product> products = em.createQuery(
                            "select p from Product p where p.id between :from and :to order by p.id", Product.class)
                    .setParameter("from", from)
                    .setParameter("to", from + PRODUCTS - 1)
                    .getResultList();
            long sum = 0;
            for (Product product : products) {
                sum += product.getId() + product.getName().length();
                for (Category category : product.getCategories()) {
                    sum += category.getName().length();
                }
                for (String tag : product.getTags()) {
                    sum += tag.length();
                }
            }
            return sum;
        }));
        counters.op();
    }

    @Benchmark
    public void inheritanceSingleTableQuery(ReadState state, StatementCounters counters, Blackhole bh) {
        long from = randomWindow(state.fixture.dataset().singleTablePersons(), PERSONS);
        bh.consume(state.fixture.inTransaction(em -> {
            long sum = 0;
            for (StPerson person : em.createQuery(
                            "select p from StPerson p where p.id between :from and :to order by p.id", StPerson.class)
                    .setParameter("from", from)
                    .setParameter("to", from + PERSONS - 1)
                    .getResultList()) {
                sum += person.getId() + person.getName().length();
                if (person instanceof StEmployee employee) {
                    sum += employee.getDepartment().length() + employee.getSalary().intValue();
                } else if (person instanceof StContractor contractor) {
                    sum += contractor.getAgency().length() + contractor.getHourlyRate().intValue();
                }
            }
            return sum;
        }));
        counters.op();
    }

    @Benchmark
    public void inheritanceJoinedQuery(ReadState state, StatementCounters counters, Blackhole bh) {
        long from = randomWindow(state.fixture.dataset().joinedPersons(), PERSONS);
        bh.consume(state.fixture.inTransaction(em -> {
            long sum = 0;
            for (JnPerson person : em.createQuery(
                            "select p from JnPerson p where p.id between :from and :to order by p.id", JnPerson.class)
                    .setParameter("from", from)
                    .setParameter("to", from + PERSONS - 1)
                    .getResultList()) {
                sum += person.getId() + person.getName().length();
                if (person instanceof JnEmployee employee) {
                    sum += employee.getDepartment().length() + employee.getSalary().intValue();
                } else if (person instanceof JnContractor contractor) {
                    sum += contractor.getAgency().length() + contractor.getHourlyRate().intValue();
                }
            }
            return sum;
        }));
        counters.op();
    }

    /** Customers of one city created after a date, ordered by last name: built with the Criteria API. */
    @Benchmark
    public void criteriaQuery(ReadState state, StatementCounters counters, Blackhole bh) {
        String city = DataGenerator.CITIES.get(random.nextInt(DataGenerator.CITIES.size()));
        LocalDate since = LocalDate.of(2020, 1, 1).plusDays(random.nextInt(730));
        bh.consume(state.fixture.inTransaction(em -> {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Customer> query = cb.createQuery(Customer.class);
            Root<Customer> customer = query.from(Customer.class);
            query.select(customer)
                    .where(cb.equal(customer.get("address").get("city"), city),
                            cb.greaterThanOrEqualTo(customer.get("createdAt"), since))
                    .orderBy(cb.asc(customer.get("lastName")), cb.asc(customer.get("id")));
            long sum = 0;
            for (Customer c : em.createQuery(query).setMaxResults(MAX_ROWS).getResultList()) {
                sum += touch(c);
            }
            return sum;
        }));
        counters.op();
    }

    /** One page of delivered orders, newest first, plus the count query a paging UI needs. */
    @Benchmark
    public void pagination(ReadState state, StatementCounters counters, Blackhole bh) {
        int page = random.nextInt(MAX_PAGES);
        bh.consume(state.fixture.inTransaction(em -> {
            long count = em.createQuery("select count(o) from PurchaseOrder o where o.status = :status", Long.class)
                    .setParameter("status", OrderStatus.DELIVERED)
                    .getSingleResult();
            long sum = count;
            for (PurchaseOrder order : em.createQuery("select o from PurchaseOrder o where o.status = :status"
                            + " order by o.orderDate desc, o.id desc", PurchaseOrder.class)
                    .setParameter("status", OrderStatus.DELIVERED)
                    .setFirstResult(page * PAGE_SIZE)
                    .setMaxResults(PAGE_SIZE)
                    .getResultList()) {
                sum += order.getId() + order.getTotal().intValue() + order.getOrderDate().getDayOfYear();
            }
            return sum;
        }));
        counters.op();
    }

    @Benchmark
    public void dtoProjection(ReadState state, StatementCounters counters, Blackhole bh) {
        LocalDate from = DataGenerator.ORDER_EPOCH.plusDays(
                random.nextInt(DataGenerator.ORDER_DAYS - PROJECTION_DAYS));
        bh.consume(state.fixture.inTransaction(em -> {
            long sum = 0;
            for (OrderSummary summary : em.createQuery("select new " + OrderSummary.class.getName()
                            + "(o.id, c.lastName, o.orderDate, o.total) from PurchaseOrder o join o.customer c"
                            + " where o.orderDate between :from and :to order by o.id", OrderSummary.class)
                    .setParameter("from", from)
                    .setParameter("to", from.plusDays(PROJECTION_DAYS - 1))
                    .setMaxResults(MAX_ROWS)
                    .getResultList()) {
                sum += summary.orderId() + summary.customerLastName().length() + summary.total().intValue();
            }
            return sum;
        }));
        counters.op();
    }

    @Benchmark
    public void aggregationGroupBy(ReadState state, StatementCounters counters, Blackhole bh) {
        LocalDate from = DataGenerator.ORDER_EPOCH.plusDays(
                random.nextInt(DataGenerator.ORDER_DAYS - AGGREGATION_DAYS));
        bh.consume(state.fixture.inTransaction(em -> {
            long sum = 0;
            for (Object[] row : em.createQuery("select c.address.city, count(o), sum(o.total)"
                            + " from PurchaseOrder o join o.customer c where o.orderDate between :from and :to"
                            + " group by c.address.city order by c.address.city", Object[].class)
                    .setParameter("from", from)
                    .setParameter("to", from.plusDays(AGGREGATION_DAYS - 1))
                    .getResultList()) {
                sum += ((String) row[0]).length() + ((Number) row[1]).longValue()
                        + ((BigDecimal) row[2]).longValue();
            }
            return sum;
        }));
        counters.op();
    }

    /** Streams a large result with {@code getResultStream()} and reads every row. */
    @Benchmark
    public void resultStream(ReadState state, StatementCounters counters, Blackhole bh) {
        DatasetInfo dataset = state.fixture.dataset();
        int rows = Math.min(STREAM_ORDERS, dataset.orders());
        long from = randomWindow(dataset.orders(), rows);
        bh.consume(state.fixture.inTransaction(em -> {
            try (Stream<PurchaseOrder> orders = em.createQuery(
                            "select o from PurchaseOrder o where o.id between :from and :to order by o.id",
                            PurchaseOrder.class)
                    .setParameter("from", from)
                    .setParameter("to", from + rows - 1)
                    .getResultStream()) {
                return orders.mapToLong(o -> o.getId() + o.getTotal().intValue() + o.getStatus().ordinal()).sum();
            }
        }));
        counters.op();
    }

    static long touch(Customer customer) {
        return customer.getId() + customer.getLastName().length() + customer.getFirstName().length()
                + customer.getEmail().length() + customer.getAddress().getCity().length();
    }

    static long touchNavigation(List<PurchaseOrder> orders) {
        long sum = 0;
        for (PurchaseOrder order : orders) {
            sum += order.getId() + order.getCustomer().getLastName().length();
            for (OrderLine line : order.getLines()) {
                sum += line.getQuantity() + line.getUnitPrice().intValue();
            }
        }
        return sum;
    }
}
