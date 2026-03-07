# TOP 11 — Type-Safe Metamodel / Static Field References

**Priority:** 11
**Effort:** High (1-2 weeks)
**Impact:** High — compile-time safety, refactoring-proof queries

---

## Gap

JPA generates `Entity_` metamodel classes with static field references, enabling
type-safe queries: `cb.equal(root.get(User_.email), "test@example.com")`.

Morphium uses string-based field names everywhere:
`query.f("email").eq("test@example.com")`. Typos compile fine but fail at runtime.
Renaming a field breaks queries silently.

## Current State in Morphium

```java
// String-based — compiles even if "emial" is a typo
query.f("emial").eq("test@example.com");  // runtime: empty result, no error

// Enum-based partial solution (manual, verbose)
public enum UserFields { name, email, active }
query.f(UserFields.email).eq("test@example.com");
// Better, but still manual maintenance — enum must match field names exactly
```

## Proposed Design

### Option A: Annotation Processor (JPA-style)

Generate `User_` classes at compile time via an annotation processor.

```java
// Generated: User_.java
@StaticMetamodel(User.class)
public class User_ {
    public static final String id = "id";
    public static final String email = "email";
    public static final String lastName = "last_name";  // respects @Property mapping
    public static final String active = "active";
}

// Usage
query.f(User_.email).eq("test@example.com");  // compile-time checked
query.sort(User_.lastName);
```

**Pros:** Standard JPA approach, zero runtime cost, full IDE support.
**Cons:** Requires annotation processor setup in build, generated code maintenance.

### Option B: Typed Field References (no code generation)

A lighter approach using a generic `Field<T,V>` type:

```java
// Manually defined (or generated)
public class UserFields {
    public static final Field<User, String> email = Field.of("email");
    public static final Field<User, String> lastName = Field.of("last_name");
    public static final Field<User, Boolean> active = Field.of("active");
}

// Query API extended
query.f(UserFields.email).eq("test@example.com");  // type-safe: eq(String)
query.f(UserFields.active).eq(true);                // type-safe: eq(Boolean)
query.f(UserFields.active).eq("yes");               // COMPILE ERROR
```

**Pros:** Type-safe values too (not just field names), no annotation processor.
**Cons:** Manual field definitions (unless generated).

### Option C: Both

Annotation processor generates `Field<T,V>` constants. Best of both worlds.

### Annotation Processor approach

1. Process all classes annotated with `@Entity` or `@Embedded`
2. For each persistent field, generate a constant:
   - Name: Java field name
   - Value: MongoDB field name (respecting `@Property`, `translateCamelCase`)
3. Output: `EntityName_.java` in the same package
4. Support incremental compilation

### Integration with Query API

```java
// In Query.java — add overload
<V> MongoField<T> f(Field<T, V> field);

// In MongoField — type-safe comparisons
MongoField<T> eq(V value);  // V is inferred from Field<T,V>
```

## Affected Files

- `morphium-metamodel-processor/` — new Maven module for annotation processor (or in main module)
- `annotations/StaticMetamodel.java` — marker for generated classes
- `Field.java` — typed field reference class
- `Query.java` — add `f(Field<T,V>)` overload
- `MongoField.java` — optionally add type-safe comparison methods
- Build: `pom.xml` — annotation processor configuration
- Tests — `MetamodelTest.java`

## Phased approach

**Phase 1 (Quick Win):** Just `Field<T,V>` class + Query overload. Users manually
define fields. Already useful.

**Phase 2:** Annotation processor generates `Entity_` classes with `Field` constants.

**Phase 3:** Type-safe `MongoField` comparisons (eq/lt/gt typed by field value type).

## Acceptance Criteria

### Phase 1
- [ ] `Field<T,V>` class exists
- [ ] `query.f(Field)` works alongside `query.f(String)`
- [ ] Type-safe field references prevent typos at compile time

### Phase 2
- [ ] Annotation processor generates `Entity_` classes
- [ ] Generated constants respect `@Property(fieldName=)` and `translateCamelCase`
- [ ] Incremental compilation supported
- [ ] Works with Maven and Gradle

### Phase 3
- [ ] `MongoField.eq(V)` is type-safe
- [ ] Passing wrong type is a compile error
