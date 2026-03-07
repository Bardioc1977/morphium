# TOP 08 — @GeneratedValue with Pluggable Strategies

**Priority:** 8
**Effort:** Medium (3-5 days)
**Impact:** Medium — unified ID generation API

---

## Gap

JPA provides `@GeneratedValue(strategy=GenerationType.AUTO/UUID/SEQUENCE/TABLE)` as
a unified annotation for automatic ID generation with pluggable strategies.

Morphium has two separate mechanisms:
- Implicit `MorphiumId`/`ObjectId` generation (if `@Id` field is MorphiumId)
- `@AutoSequence` for counter-collection-based sequences

No UUID generation, no strategy abstraction, no way to plug custom generators.

## Current State in Morphium

```java
@Entity
public class User {
    @Id public MorphiumId id;  // auto-generated ObjectId — implicit, no annotation

    @AutoSequence(name = "user_seq", startValue = 1000)
    public long sequenceNr;    // separate annotation, separate concern
}
```

## Proposed Design

### Strategy Enum

```java
public enum GenerationType {
    AUTO,           // provider chooses (ObjectId for MorphiumId, UUID for String, sequence for long)
    OBJECT_ID,      // MongoDB ObjectId (current implicit behavior)
    UUID,           // java.util.UUID.randomUUID().toString()
    SEQUENCE        // counter-collection based (current @AutoSequence behavior)
}
```

### Annotation

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface GeneratedValue {
    GenerationType strategy() default GenerationType.AUTO;
    String generator() default "";  // name for sequence generator config
}
```

### Optional: custom generator SPI

```java
public interface IdGenerator<T> {
    T generate(Morphium morphium, Class<?> entityType, String fieldName);
}
```

### Usage

```java
@Entity
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.OBJECT_ID)
    public MorphiumId id;
}

@Entity
public class ApiKey {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public String id;    // → "550e8400-e29b-41d4-a716-446655440000"
}

@Entity
public class Invoice {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "invoice_seq")
    public long id;      // → 1001, 1002, 1003, ...
}

@Entity
public class Event {
    @Id
    @GeneratedValue  // AUTO: String field → UUID, MorphiumId field → ObjectId, long → sequence
    public String id;
}
```

### AUTO strategy resolution

| Field Type | Generated Value |
|---|---|
| `MorphiumId` / `ObjectId` | `new MorphiumId()` |
| `String` | `UUID.randomUUID().toString()` |
| `long` / `Long` / `int` / `Integer` | Sequence (counter collection) |

### Backward compatibility

- `@Id` without `@GeneratedValue` on `MorphiumId` field continues to work as before
- `@AutoSequence` continues to work (not deprecated — it's for non-ID fields)
- `@GeneratedValue` is only for `@Id` fields

### Implementation

Integrate into `Morphium.setIdIfNull()`:
1. Check if `@Id` field has `@GeneratedValue`
2. If yes, resolve strategy (explicit or AUTO)
3. Call the appropriate generator
4. Set the value on the field

## Affected Files

- `annotations/GeneratedValue.java` — new annotation
- `annotations/GenerationType.java` — new enum
- `IdGenerator.java` — new SPI interface (optional)
- `Morphium.java` / `MorphiumBase.java` — extend `setIdIfNull()` with strategy dispatch
- `AnnotationAndReflectionHelper.java` — scan for `@GeneratedValue`
- Tests — `GeneratedValueTest.java`

## Acceptance Criteria

- [ ] `@GeneratedValue(strategy=OBJECT_ID)` generates MorphiumId
- [ ] `@GeneratedValue(strategy=UUID)` generates UUID string
- [ ] `@GeneratedValue(strategy=SEQUENCE)` uses counter collection
- [ ] `@GeneratedValue` (AUTO) infers strategy from field type
- [ ] Existing `@Id` on MorphiumId without `@GeneratedValue` unchanged
- [ ] `@AutoSequence` on non-ID fields unchanged
- [ ] Works with `store()` and `storeList()`
- [ ] Works with InMemoryDriver
