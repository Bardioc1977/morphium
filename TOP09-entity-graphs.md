# TOP 09 — Entity Graphs / Fetch Plans

**Priority:** 9
**Effort:** High (1-2 weeks)
**Impact:** High — solves N+1 problem, context-dependent loading

---

## Gap

JPA's Entity Graph API (`@NamedEntityGraph`, programmatic `EntityGraph<T>`) controls
which relationships and fields to eagerly fetch. The same entity can be loaded with
different "shapes" depending on the use case.

Morphium has `@Reference(lazyLoading=true/false)` which is static (compile-time) and
binary. `Query.project()` controls field projection but not reference resolution depth.
There's no way to say "in this query, resolve customer but not customer.orders."

## Current State in Morphium

```java
// Static — always lazy OR always eager
@Reference(lazyLoading = true)
public Customer customer;  // always lazy, even when you always need it

// Or always eager
@Reference(lazyLoading = false)
public Customer customer;  // always loads, even in list views where you don't need it
```

No per-query control over which references to resolve.

## Proposed Design

### Annotation-based (static, reusable)

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(NamedEntityGraphs.class)
public @interface NamedEntityGraph {
    String name();
    String[] attributeNodes();     // fields to eagerly include
    String[] subgraphs() default {};  // dot-notation for nested refs: "customer.address"
}
```

### Usage (annotation)

```java
@Entity
@NamedEntityGraph(name = "Order.summary", attributeNodes = {"orderDate", "total"})
@NamedEntityGraph(name = "Order.detail", attributeNodes = {"orderDate", "total", "customer", "items"},
                  subgraphs = {"customer.address"})
public class Order {
    @Id public MorphiumId id;
    public Date orderDate;
    public long total;

    @Reference(lazyLoading = true)
    public Customer customer;

    @Reference(lazyLoading = true)
    public List<OrderItem> items;
}
```

### Programmatic API

```java
// Fluent builder
EntityGraph<Order> graph = morphium.createEntityGraph(Order.class)
    .addAttributes("orderDate", "total", "customer")
    .addSubgraph("customer", g -> g.addAttributes("name", "address"));

// Apply to query
List<Order> orders = morphium.createQueryFor(Order.class)
    .f("status").eq("SHIPPED")
    .withEntityGraph(graph)   // or: .withEntityGraph("Order.detail")
    .asList();
```

### Fetch vs. Load semantics

| Mode | Behavior |
|---|---|
| **Fetch Graph** | Only attributes in the graph are loaded; references not in the graph are left as null/proxy |
| **Load Graph** | Attributes in the graph are eagerly loaded; others use their default fetch type |

Default: Load Graph (additive, non-breaking).

### Implementation approach

1. `EntityGraph<T>` data structure holding field names + nested subgraphs
2. `@NamedEntityGraph` parsed at entity registration, stored in a registry
3. `Query.withEntityGraph()` attaches graph to the query
4. During deserialization in `ObjectMapperImpl`, check if an entity graph is active:
   - For `@Reference` fields: resolve eagerly if in graph, skip/proxy if not
   - For regular fields: apply as projection
5. Subgraphs recurse: when resolving a reference, apply the sub-EntityGraph

### Complexity notes

- This is the most architecturally significant change in this list
- Requires threading the EntityGraph through the deserialization pipeline
- Must handle circular graphs gracefully (cycle detection already exists)
- Consider starting with a simpler version: just control which `@Reference` fields
  to resolve per query, without nested subgraphs

## Affected Files

- `EntityGraph.java` — new data structure
- `annotations/NamedEntityGraph.java` — new annotation
- `annotations/NamedEntityGraphs.java` — repeatable container
- `Query.java` / `QueryImpl.java` — add `withEntityGraph()` method
- `ObjectMapperImpl.java` — entity-graph-aware reference resolution
- `AnnotationAndReflectionHelper.java` — parse `@NamedEntityGraph`
- `Morphium.java` — `createEntityGraph()` factory method, named graph registry
- Tests — `EntityGraphTest.java`

## Acceptance Criteria

- [ ] `@NamedEntityGraph` defines a reusable fetch plan
- [ ] Programmatic `EntityGraph` builder works
- [ ] `query.withEntityGraph()` controls which references are resolved
- [ ] References not in the graph remain unresolved (null or lazy proxy)
- [ ] Nested subgraphs control depth of reference resolution
- [ ] Works with `@Reference` collections (List, Set, Map)
- [ ] Circular references handled gracefully
- [ ] Works with InMemoryDriver
