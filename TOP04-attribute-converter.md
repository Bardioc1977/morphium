# TOP 04 — @Convert / AttributeConverter — Per-Field Type Conversion

**Priority:** 4
**Effort:** Medium (3-5 days)
**Impact:** High — enables custom type mapping per field

---

## Gap

JPA provides `@Convert(converter=MoneyConverter.class)` to apply custom type
conversion on individual fields. The `AttributeConverter<X,Y>` interface has two
methods: `convertToDatabaseColumn(X)` and `convertToEntityAttribute(Y)`.

Morphium has global type mappers via `ObjectMapperImpl.registerTypeMapper()`, but
no per-field conversion. If two fields of the same Java type need different storage
formats, there's no clean solution.

## Current State in Morphium

```java
// Global type mapper — applies to ALL fields of type Money
morphium.getMapper().registerTypeMapper(Money.class, new MorphiumTypeMapper<Money>() {
    // applies everywhere, no per-field control
});
```

Cannot do: store `Money` as `{amount, currency}` subdocument in one field but as
a plain `long` (cents) in another.

## Proposed Design

### Interface

```java
public interface AttributeConverter<X, Y> {
    Y convertToDocument(X attribute);
    X convertToAttribute(Y dbValue);
}
```

### Annotation

```java
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Convert {
    Class<? extends AttributeConverter<?, ?>> converter();
    boolean disableConversion() default false;
}
```

### Optional: auto-apply

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Converter {
    boolean autoApply() default false;  // apply to all fields of the source type
}
```

### Usage examples

```java
@Converter
public class MoneyToDocumentConverter implements AttributeConverter<Money, Map<String, Object>> {
    @Override
    public Map<String, Object> convertToDocument(Money money) {
        return Map.of("amount", money.getAmount(), "currency", money.getCurrency().name());
    }

    @Override
    public Money convertToAttribute(Map<String, Object> doc) {
        return new Money((long) doc.get("amount"), Currency.valueOf((String) doc.get("currency")));
    }
}

public class MoneyToCentsConverter implements AttributeConverter<Money, Long> {
    @Override public Long convertToDocument(Money m) { return m.toCents(); }
    @Override public Money convertToAttribute(Long cents) { return Money.fromCents(cents); }
}

@Entity
public class Invoice {
    @Id public MorphiumId id;

    @Convert(converter = MoneyToDocumentConverter.class)
    public Money total;          // stored as {amount: 1999, currency: "EUR"}

    @Convert(converter = MoneyToCentsConverter.class)
    public Money discount;       // stored as 500 (long)
}
```

### Implementation approach

1. `AnnotationAndReflectionHelper` scans fields for `@Convert` at entity registration
2. Converter instances are cached (stateless, thread-safe, instantiated via no-arg constructor)
3. `ObjectMapperImpl.serialize()` — before writing a field, check for converter; if present, call `convertToDocument()`
4. `ObjectMapperImpl.deserialize()` — after reading a field value, check for converter; if present, call `convertToAttribute()`
5. Converters take precedence over global TypeMappers for the annotated field
6. `autoApply=true` converters are used for all fields of the source type unless overridden by a field-level `@Convert`

## Affected Files

- `annotations/Convert.java` — new annotation
- `annotations/Converter.java` — new annotation (optional, for autoApply)
- `AttributeConverter.java` — new interface
- `ObjectMapperImpl.java` — integrate converter lookup in serialize/deserialize
- `AnnotationAndReflectionHelper.java` — scan for `@Convert`, cache converters
- Tests — `AttributeConverterTest.java`

## Acceptance Criteria

- [ ] `@Convert` on a field applies the specified converter
- [ ] Two fields of the same type can use different converters
- [ ] Converter takes precedence over global TypeMapper
- [ ] `autoApply=true` works for all fields of the converted type
- [ ] `disableConversion=true` disables autoApply for a specific field
- [ ] Converters work with `@Embedded` fields
- [ ] Converters work with List/Set/Map element types
- [ ] Works with InMemoryDriver
- [ ] Thread-safe (converters are stateless singletons)
