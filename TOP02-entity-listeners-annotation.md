# TOP 02 — @EntityListeners — Per-Entity Listener Assignment

**Priority:** 2 (Quick Win)
**Effort:** Low (1-2 days)
**Impact:** Medium — clean separation of cross-cutting concerns

---

## Gap

JPA allows assigning listener classes directly on an entity via
`@EntityListeners({AuditListener.class, ValidationListener.class})`.
Each listener class contains callback methods (`@PrePersist`, `@PostLoad`, etc.)
that fire only for that entity type.

Morphium's `MorphiumStorageListener<T>` is registered globally on the `Morphium`
instance. Listeners must internally filter by entity type, leading to boilerplate
`instanceof` checks.

## Current State in Morphium

```java
// Global listener — fires for ALL entity types
morphium.addListener(new MorphiumStorageListener<Object>() {
    @Override
    public void preStore(Morphium m, Object entity, boolean isNew) {
        if (entity instanceof Order) {  // manual filtering
            ((Order) entity).setAuditTimestamp(new Date());
        }
    }
});
```

## Proposed Design

### Annotation

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EntityListeners {
    Class<?>[] value();
}
```

### Listener class pattern

Listener classes use the existing lifecycle annotations (`@PreStore`, `@PostStore`,
`@PreRemove`, `@PostRemove`, `@PostLoad`) on their methods. Methods receive the
entity as parameter.

```java
public class OrderAuditListener {
    @PreStore
    public void beforeStore(Object entity) {
        ((Order) entity).setAuditTimestamp(new Date());
    }

    @PostRemove
    public void afterRemove(Object entity) {
        AuditLog.record("Deleted order: " + ((Order) entity).getId());
    }
}

@Entity
@EntityListeners({OrderAuditListener.class, ValidationListener.class})
public class Order { ... }
```

### Invocation order (JPA-compatible)

1. Default listeners (global `MorphiumStorageListener` instances)
2. `@EntityListeners` classes (in declaration order)
3. Entity-level `@PreStore`/`@PostStore` methods

### Implementation approach

- `AnnotationAndReflectionHelper` scans for `@EntityListeners` at registration time
- Listener instances are cached per class (instantiated once via no-arg constructor)
- Lifecycle dispatch in `Morphium.firePreStore()` etc. resolves entity-specific
  listeners before invoking them
- CDI-aware instantiation possible via optional SPI (for Quarkus integration)

## Affected Files

- `annotations/EntityListeners.java` — new annotation
- `AnnotationAndReflectionHelper.java` — scan and cache listener classes
- `Morphium.java` — extend `firePreStore/firePostStore/...` methods
- `MorphiumBase.java` — possibly shared dispatch logic
- Tests — `EntityListenersTest.java`

## Acceptance Criteria

- [ ] `@EntityListeners` on an entity class triggers only for that type
- [ ] Multiple listeners are invoked in declaration order
- [ ] All 7 lifecycle events supported (`@PreStore`, `@PostStore`, `@PreRemove`, `@PostRemove`, `@PostLoad`, `@PreUpdate`, `@PostUpdate`)
- [ ] Global `MorphiumStorageListener` and `@EntityListeners` coexist
- [ ] Listener instantiation works with no-arg constructor
- [ ] Works with InMemoryDriver
