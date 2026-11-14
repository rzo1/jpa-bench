// Prints seed and "sizes" of src/jpa_bench_viz/datasets.json: row counts and checksums of S, M and L.
// jshell --class-path bench-data/target/classes bench-viz/scripts/datasets.jsh
import io.github.rzo1.jpabench.data.*;
import io.github.rzo1.jpabench.data.Rows.*;

class Counts implements DatasetVisitor {
    long category, product, productCategory, productTag, customer, orders, orderLine, stPerson, stEmployee,
        jnPerson, jnEmployee;
    public void category(CategoryRow r) { category++; }
    public void product(ProductRow r) { product++; productCategory += r.categoryIds().size(); productTag += r.tags().size(); }
    public void customer(CustomerRow r) { customer++; }
    public void order(OrderRow r) { orders++; orderLine += r.lines().size(); }
    public void singleTablePerson(PersonRow r) { stPerson++; if (r.employee()) stEmployee++; }
    public void joinedPerson(PersonRow r) { jnPerson++; if (r.employee()) jnEmployee++; }
}

var out = new StringBuilder("{\n  \"seed\": " + DataGenerator.DEFAULT_SEED + ",\n  \"sizes\": {");
var first = true;
for (var size : DatasetSize.values()) {
    var c = new Counts();
    var checksum = new DataGenerator(DataGenerator.DEFAULT_SEED, size).generate(c);
    out.append(first ? "\n" : ",\n");
    first = false;
    out.append("    \"" + size + "\": {\"scale\": " + size.scale() + ", \"checksum\": \"" + checksum.hex()
        + "\", \"records\": " + checksum.records() + ", \"tables\": {"
        + "\"CATEGORY\": " + c.category + ", \"PRODUCT\": " + c.product
        + ", \"PRODUCT_CATEGORY\": " + c.productCategory + ", \"PRODUCT_TAG\": " + c.productTag
        + ", \"CUSTOMER\": " + c.customer + ", \"ORDERS\": " + c.orders + ", \"ORDER_LINE\": " + c.orderLine
        + ", \"ST_PERSON\": " + c.stPerson + ", \"JN_PERSON\": " + c.jnPerson
        + ", \"JN_EMPLOYEE\": " + c.jnEmployee + ", \"JN_CONTRACTOR\": " + (c.jnPerson - c.jnEmployee) + "}}");
}
out.append("\n  }\n}");
System.out.println(out);
/exit
