# TOP 05 — @Enumerated(STRING/ORDINAL) — Enum Storage Control

**Priority:** 5 (Quick Win)
**Effort:** Low (< 1 day)
**Impact:** Low-Medium — control over enum persistence format

---

## Gap

JPA's `@Enumerated` controls whether enum values are stored as their name (STRING)
or ordinal position (ORDINAL). Morphium always stores enums as their String name.

## Current State in Morphium

```java
@Entity
public class Order {
    public Status status;  // always stored as "PENDING", "SHIPPED", etc.
}

public enum Status { PENDING, PROCESSING, SHIPPED, DELIVERED }
// MongoDB: { "status": "SHIPPED" }
```

No way to store as ordinal (integer), which can be desirable for:
- Storage efficiency in large collections
- Compatibility with external systems that use numeric codes

## Proposed Design

### Annotation

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Enumerated {
    EnumType value() default EnumType.STRING;
}

public enum EnumType {
    STRING,   // store as enum name (current default)
    ORDINAL   // store as ordinal integer
}
```

### Usage

```java
@Entity
public class Order {
    public Status status;                        // default: STRING → "SHIPPED"

    @Enumerated(EnumType.ORDINAL)
    public Priority priority;                    // → 2 (integer)
}
```

### Implementation

In `ObjectMapperImpl`:
- **serialize:** if field has `@Enumerated(ORDINAL)`, call `enumValue.ordinal()` instead of `enumValue.name()`
- **deserialize:** if field has `@Enumerated(ORDINAL)`, call `EnumClass.values()[ordinal]` instead of `Enum.valueOf()`
- Default behavior (no annotation) remains STRING — fully backward compatible

## Affected Files

- `annotations/Enumerated.java` — new annotation
- `annotations/EnumType.java` — new enum
- `ObjectMapperImpl.java` — check for `@Enumerated` in enum serialize/deserialize
- Tests — `EnumeratedTest.java`

## Acceptance Criteria

- [ ] Default behavior unchanged (STRING without annotation)
- [ ] `@Enumerated(EnumType.ORDINAL)` stores enum as integer
- [ ] Deserialization correctly reconstructs enum from ordinal
- [ ] Works with List/Set of enums
- [ ] Works with Map keys/values that are enums
- [ ] Invalid ordinal on read produces clear error message
