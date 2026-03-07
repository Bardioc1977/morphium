# TOP 10 — @NamedQuery — Predefined, Reusable Queries

**Priority:** 10
**Effort:** Medium (2-3 days)
**Impact:** Low-Medium — reusability and startup validation

---

## Gap

JPA's `@NamedQuery` defines reusable queries on entity classes that are validated
at startup and can be referenced by name.

Morphium has no equivalent. All queries are built programmatically at call site.
No reuse mechanism, no startup validation.

## Current State in Morphium

```java
// Same query built in multiple places
Query<User> q1 = morphium.createQueryFor(User.class)
    .f("active").eq(true)
    .f("role").eq("ADMIN")
    .sort("lastName");

// Duplicated elsewhere
Query<User> q2 = morphium.createQueryFor(User.class)
    .f("active").eq(true)
    .f("role").eq("ADMIN")
    .sort("lastName");
```

## Proposed Design

### Annotation

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(NamedQueries.class)
public @interface NamedQuery {
    String name();
    String filter();              // MongoDB filter as JSON: {"active": true, "role": ":role"}
    String sort() default "";     // sort as JSON: {"lastName": 1}
    String projection() default "";  // projection as JSON: {"name": 1, "email": 1}
    int limit() default -1;
}
```

### Usage (definition)

```java
@Entity
@NamedQuery(name = "User.activeAdmins",
            filter = "{\"active\": true, \"role\": \"ADMIN\"}",
            sort = "{\"last_name\": 1}")
@NamedQuery(name = "User.byEmail",
            filter = "{\"email\": \":email\"}")
public class User {
    @Id public MorphiumId id;
    public String email;
    public String lastName;
    public boolean active;
    public String role;
}
```

### Usage (execution)

```java
// Execute named query
List<User> admins = morphium.createNamedQuery("User.activeAdmins", User.class)
    .asList();

// With parameter binding
List<User> users = morphium.createNamedQuery("User.byEmail", User.class)
    .setParameter("email", "test@example.com")
    .asList();
```

### Alternative: programmatic registration

```java
// For queries too complex for JSON annotation
morphium.registerNamedQuery("User.complexQuery", User.class, m -> {
    return m.createQueryFor(User.class)
        .f("active").eq(true)
        .f("role").eq("ADMIN")
        .sort("lastName");
});
```

### Startup validation

On `Morphium` initialization, all `@NamedQuery` annotations are parsed:
- Validate that filter JSON is syntactically correct
- Validate that referenced field names exist on the entity
- Log warnings for unknown fields (typos)
- Store in a `Map<String, NamedQueryDefinition>` for lookup

## Affected Files

- `annotations/NamedQuery.java` — new annotation
- `annotations/NamedQueries.java` — repeatable container
- `NamedQueryDefinition.java` — internal data class
- `Morphium.java` — `createNamedQuery()`, `registerNamedQuery()`, startup validation
- `AnnotationAndReflectionHelper.java` — scan for `@NamedQuery`
- Tests — `NamedQueryTest.java`

## Acceptance Criteria

- [ ] `@NamedQuery` on entity class is parsed at startup
- [ ] `morphium.createNamedQuery(name, type)` returns a configured `Query<T>`
- [ ] Parameter binding (`:paramName`) works
- [ ] Invalid filter JSON detected at startup
- [ ] Unknown field names produce warning at startup
- [ ] Programmatic registration as alternative to annotations
- [ ] Works with InMemoryDriver
