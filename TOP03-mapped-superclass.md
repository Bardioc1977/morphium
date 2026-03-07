# TOP 03 — @MappedSuperclass — Base Class Without Collection

**Priority:** 3 (Quick Win)
**Effort:** Low (< 1 day)
**Impact:** Medium — very common pattern for shared fields

---

## Gap

JPA's `@MappedSuperclass` marks a class whose persistent fields are inherited by
entity subclasses, but the class itself has no table/collection and cannot be
queried directly.

Morphium has no formal equivalent. Base classes work via Java inheritance, but
there's no way to declare intent: "this class provides fields but is not an entity."
Currently you simply omit `@Entity` on the base class, which works but is implicit
and undocumented.

## Current State in Morphium

```java
// Works, but intention is unclear — is @Entity missing by accident?
public abstract class BaseEntity {
    @Id public MorphiumId id;
    @CreationTime public Date createdAt;
    @LastChange public Date updatedAt;
    @Version public long version;
}

@Entity
public class User extends BaseEntity {
    public String name;
    public String email;
}
```

Morphium already inherits fields from parent classes. The gap is purely declarative.

## Proposed Design

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MappedSuperclass {
}
```

### Semantics

- Classes annotated with `@MappedSuperclass` are explicitly NOT entities
- Their fields (including annotated fields like `@Id`, `@Version`, `@CreationTime`)
  are inherited by entity subclasses
- `createQueryFor(BaseEntity.class)` throws `IllegalArgumentException`
  ("@MappedSuperclass cannot be queried directly")
- `AnnotationAndReflectionHelper.isEntity()` returns `false` for `@MappedSuperclass`
- `@MappedSuperclass` and `@Entity` on the same class is an error (fail at startup)

### Usage

```java
@MappedSuperclass
public abstract class AuditableEntity {
    @Id public MorphiumId id;
    @CreationTime public Date createdAt;
    @LastChange public Date updatedAt;
    @Version public long version;
    public String createdBy;
}

@Entity(collectionName = "users")
public class User extends AuditableEntity {
    public String name;
    public String email;
}

@Entity(collectionName = "orders")
public class Order extends AuditableEntity {
    public String product;
    public int quantity;
}
```

## Affected Files

- `annotations/MappedSuperclass.java` — new annotation
- `AnnotationAndReflectionHelper.java` — recognize annotation, validate no dual use with `@Entity`
- `Morphium.java` — guard `createQueryFor()` and `store()` against direct use of `@MappedSuperclass` types
- Tests — `MappedSuperclassTest.java`

## Acceptance Criteria

- [ ] `@MappedSuperclass` fields are inherited by `@Entity` subclasses
- [ ] `@Id`, `@Version`, `@CreationTime`, `@LastChange` work when defined in `@MappedSuperclass`
- [ ] Direct query/store of `@MappedSuperclass` type throws clear error
- [ ] `@MappedSuperclass` + `@Entity` on same class detected and rejected at startup
- [ ] Existing behavior (base class without annotation) unchanged
