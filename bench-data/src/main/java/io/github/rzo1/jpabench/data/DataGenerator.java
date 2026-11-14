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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.TreeSet;

import io.github.rzo1.jpabench.data.Rows.CategoryRow;
import io.github.rzo1.jpabench.data.Rows.CustomerRow;
import io.github.rzo1.jpabench.data.Rows.OrderLineRow;
import io.github.rzo1.jpabench.data.Rows.OrderRow;
import io.github.rzo1.jpabench.data.Rows.PersonRow;
import io.github.rzo1.jpabench.data.Rows.ProductRow;

/**
 * Deterministic generator of the benchmark dataset: all values are drawn in a fixed order from one
 * {@link SplittableRandom}, so seed and size determine the rows. Ids are dense, {@code 1..n} per table.
 */
public final class DataGenerator {

    /** The seed used for every published run. */
    public static final long DEFAULT_SEED = 20_260_101L;

    /** Orders are dated within {@code [ORDER_EPOCH, ORDER_EPOCH + ORDER_DAYS)}. */
    public static final LocalDate ORDER_EPOCH = LocalDate.of(2024, 1, 1);
    public static final int ORDER_DAYS = 730;

    public static final List<String> STATUSES = List.of("NEW", "PAID", "SHIPPED", "DELIVERED", "CANCELLED");
    private static final int[] STATUS_WEIGHTS = {10, 15, 15, 55, 5};

    public static final List<String> CITIES = List.of(
            "Amsterdam", "Athens", "Barcelona", "Berlin", "Bern", "Bratislava", "Brussels", "Bucharest", "Budapest",
            "Copenhagen", "Dublin", "Edinburgh", "Florence", "Frankfurt", "Geneva", "Hamburg", "Helsinki",
            "Istanbul", "Krakow", "Lisbon", "Ljubljana", "London", "Luxembourg", "Lyon", "Madrid", "Malmo",
            "Marseille", "Milan", "Munich", "Naples", "Nice", "Oslo", "Paris", "Porto", "Prague", "Reykjavik",
            "Riga", "Rome", "Rotterdam", "Salzburg", "Seville", "Sofia", "Stockholm", "Tallinn", "Turin",
            "Valencia", "Vienna", "Vilnius", "Warsaw", "Zurich");
    private static final List<String> COUNTRIES = List.of(
            "NL", "GR", "ES", "DE", "CH", "SK", "BE", "RO", "HU", "DK", "IE", "GB", "IT", "DE", "CH", "DE", "FI",
            "TR", "PL", "PT", "SI", "GB", "LU", "FR", "ES", "SE", "FR", "IT", "DE", "IT", "FR", "NO", "FR", "PT",
            "CZ", "IS", "LV", "IT", "NL", "AT", "ES", "BG", "SE", "EE", "IT", "ES", "AT", "LT", "PL", "CH");

    private static final List<String> FIRST_NAMES = List.of(
            "Ada", "Alan", "Alice", "Anna", "Ben", "Carla", "Chris", "Clara", "David", "Diana", "Elena", "Emil",
            "Eva", "Felix", "Frida", "George", "Grace", "Hanna", "Hugo", "Ida", "Ivan", "Jana", "Jonas", "Julia",
            "Karl", "Lara", "Leo", "Lina", "Marc", "Maria", "Nina", "Noah", "Olga", "Oscar", "Paul", "Petra",
            "Rosa", "Sven", "Tara", "Tom");
    public static final List<String> LAST_NAMES = List.of(
            "Adams", "Baker", "Bauer", "Becker", "Berg", "Brown", "Clark", "Costa", "Davis", "Dubois", "Evans",
            "Fischer", "Garcia", "Green", "Hall", "Hansen", "Hoffmann", "Jansen", "Johnson", "Keller", "King",
            "Klein", "Kowalski", "Lambert", "Larsen", "Lewis", "Lopez", "Martin", "Meyer", "Miller", "Moreau",
            "Muller", "Nagy", "Nielsen", "Novak", "Olsen", "Peters", "Petit", "Richter", "Rossi", "Russo",
            "Schmidt", "Schneider", "Schulz", "Silva", "Smith", "Sousa", "Taylor", "Thomas", "Vogel", "Wagner",
            "Walker", "Weber", "White", "Wilson", "Wolf", "Wright", "Young", "Zimmer", "Zowalski");
    private static final List<String> STREETS = List.of(
            "Main Street", "High Street", "Station Road", "Church Lane", "Park Avenue", "Mill Road", "Market Square",
            "River Walk", "Garden Row", "Harbour View");
    private static final List<String> ADJECTIVES = List.of(
            "Compact", "Deluxe", "Eco", "Ergonomic", "Heavy", "Light", "Modern", "Portable", "Premium", "Rugged",
            "Smart", "Vintage");
    private static final List<String> NOUNS = List.of(
            "Backpack", "Blender", "Camera", "Chair", "Desk", "Drill", "Headset", "Kettle", "Keyboard", "Lamp",
            "Monitor", "Mouse", "Speaker", "Tent", "Watch");
    public static final List<String> CATEGORY_NAMES = List.of(
            "Audio", "Books", "Camping", "Cleaning", "Computers", "Cooking", "Cycling", "Decor", "Fitness",
            "Furniture", "Gaming", "Garden", "Health", "Kids", "Lighting", "Music", "Office", "Outdoor", "Pets",
            "Phones", "Photo", "Storage", "Tools", "Travel", "Wearables");
    public static final List<String> TAGS = List.of(
            "bestseller", "bundle", "clearance", "eco", "exclusive", "gift", "handmade", "imported", "limited",
            "new", "refurbished", "sale", "seasonal", "staff-pick", "top-rated", "warranty");
    public static final List<String> DEPARTMENTS = List.of(
            "Engineering", "Finance", "HR", "Legal", "Logistics", "Marketing", "Operations", "Purchasing",
            "Sales", "Support");
    private static final List<String> AGENCIES = List.of(
            "Acme Staffing", "Blue Talent", "Contract Pros", "Delta Experts", "Freelance Hub", "Nova Partners",
            "Prime Consult", "Summit Labs");

    private final long seed;
    private final DatasetSize size;

    public DataGenerator(long seed, DatasetSize size) {
        this.seed = seed;
        this.size = size;
    }

    public long seed() {
        return seed;
    }

    public DatasetSize size() {
        return size;
    }

    /** Returns the unique e-mail address of customer {@code id}. */
    public static String customerEmail(long id) {
        return "customer" + id + "@example.com";
    }

    /** Returns the SKU of product {@code id}. */
    public static String productSku(long id) {
        return String.format(Locale.ROOT, "SKU-%08d", id);
    }

    /**
     * Generates the dataset and passes every row to {@code visitor}.
     *
     * @return the checksum of all generated rows
     */
    public Checksum generate(DatasetVisitor visitor) {
        SplittableRandom random = new SplittableRandom(seed);
        Checksum checksum = new Checksum();

        for (int i = 0; i < DatasetSize.CATEGORIES; i++) {
            CategoryRow row = new CategoryRow(i + 1, CATEGORY_NAMES.get(i));
            checksum.update(row);
            visitor.category(row);
        }

        BigDecimal[] prices = new BigDecimal[size.products() + 1];
        for (long id = 1; id <= size.products(); id++) {
            BigDecimal price = cents(random, 100, 50_000);
            prices[(int) id] = price;
            String name = pick(random, ADJECTIVES) + " " + pick(random, NOUNS) + " " + id;
            List<Long> categories = distinct(random, 1 + random.nextInt(3), DatasetSize.CATEGORIES).stream()
                    .map(c -> (long) c + 1).toList();
            List<String> tags = distinct(random, random.nextInt(5), TAGS.size()).stream().map(TAGS::get).toList();
            ProductRow row = new ProductRow(id, productSku(id), name, price, categories, tags);
            checksum.update(row);
            visitor.product(row);
        }

        LocalDate customerEpoch = LocalDate.of(2020, 1, 1);
        for (long id = 1; id <= size.customers(); id++) {
            int city = random.nextInt(CITIES.size());
            CustomerRow row = new CustomerRow(id, pick(random, FIRST_NAMES), pick(random, LAST_NAMES),
                    customerEmail(id), customerEpoch.plusDays(random.nextInt(1_460)),
                    (1 + random.nextInt(200)) + " " + pick(random, STREETS), CITIES.get(city),
                    String.format(Locale.ROOT, "%05d", random.nextInt(100_000)), COUNTRIES.get(city));
            checksum.update(row);
            visitor.customer(row);
        }

        long lineId = 0;
        for (long id = 1; id <= size.orders(); id++) {
            long customerId = 1 + random.nextInt(size.customers());
            LocalDate date = ORDER_EPOCH.plusDays(random.nextInt(ORDER_DAYS));
            String status = weightedStatus(random);
            int lineCount = 1 + random.nextInt(5);
            List<OrderLineRow> lines = new ArrayList<>(lineCount);
            BigDecimal total = BigDecimal.ZERO;
            for (int n = 1; n <= lineCount; n++) {
                long productId = 1 + random.nextInt(size.products());
                int quantity = 1 + random.nextInt(10);
                BigDecimal unitPrice = prices[(int) productId];
                total = total.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
                lines.add(new OrderLineRow(++lineId, productId, n, quantity, unitPrice));
            }
            OrderRow row = new OrderRow(id, 0, customerId, date, status, total, List.copyOf(lines));
            checksum.update(row);
            visitor.order(row);
        }

        for (long id = 1; id <= size.persons(); id++) {
            PersonRow row = person(random, id);
            checksum.update(row);
            visitor.singleTablePerson(row);
        }
        for (long id = 1; id <= size.persons(); id++) {
            PersonRow row = person(random, id);
            checksum.update(row);
            visitor.joinedPerson(row);
        }
        return checksum;
    }

    private static PersonRow person(SplittableRandom random, long id) {
        String name = pick(random, FIRST_NAMES) + " " + pick(random, LAST_NAMES);
        String email = "person" + id + "@example.com";
        if (random.nextInt(10) < 7) {
            return new PersonRow(id, Rows.EMPLOYEE, name, email, pick(random, DEPARTMENTS),
                    cents(random, 3_000_000, 15_000_000), null, null);
        }
        return new PersonRow(id, Rows.CONTRACTOR, name, email, null, null, pick(random, AGENCIES),
                cents(random, 4_000, 20_000));
    }

    private static String weightedStatus(SplittableRandom random) {
        int r = random.nextInt(100);
        for (int i = 0; i < STATUS_WEIGHTS.length; i++) {
            r -= STATUS_WEIGHTS[i];
            if (r < 0) {
                return STATUSES.get(i);
            }
        }
        return STATUSES.getLast();
    }

    /** Returns a price in {@code [minCents, maxCents)} with scale 2. */
    public static BigDecimal cents(SplittableRandom random, int minCents, int maxCents) {
        return BigDecimal.valueOf(minCents + random.nextInt(maxCents - minCents), 2);
    }

    private static <T> T pick(SplittableRandom random, List<T> values) {
        return values.get(random.nextInt(values.size()));
    }

    /** Returns {@code count} distinct indexes of {@code [0, bound)}, sorted. */
    private static TreeSet<Integer> distinct(SplittableRandom random, int count, int bound) {
        TreeSet<Integer> result = new TreeSet<>();
        while (result.size() < count) {
            result.add(random.nextInt(bound));
        }
        return result;
    }
}
