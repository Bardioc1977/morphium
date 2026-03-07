# TOP 07 — Extended Cascade Types for @Reference

**Priority:** 7
**Effort:** Medium (3-5 days)
**Impact:** Medium — completes the cascade story beyond DELETE

---

## Gap

JPA defines `CascadeType.PERSIST`, `MERGE`, `REMOVE`, `REFRESH`, `DETACH`, `ALL`.

Morphium currently supports:
- `@Reference(automaticStore=true)` — equivalent to CASCADE PERSIST
- `@Reference(cascadeDelete=true)` — equivalent to CASCADE REMOVE
- `@Reference(orphanRemoval=true)` — JPA-equivalent orphanRemoval

Missing:
- **CASCADE REFRESH** — when refreshing the parent, also refresh referenced entities
- **CASCADE ALL** — convenience for "all cascade types"
- **Explicit CascadeType enum** — instead of boolean flags, a proper enum

## Current State in Morphium

```java
@Reference(automaticStore = true, cascadeDelete = true, orphanRemoval = true)
public Customer customer;
// Three booleans — no fine-grained control, not extensible
```

## Proposed Design

### CascadeType Enum

```java
public enum CascadeType {
    PERSIST,       // auto-store referenced entities (= current automaticStore)
    REMOVE,        // delete referenced entities (= current cascadeDelete)
    REFRESH,       // refresh referenced entities when parent is refreshed
    ALL            // all of the above
}
```

### Updated @Reference

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Reference {
    String fieldName() default ".";
    boolean lazyLoading() default false;
    String targetCollection() default ".";
    boolean orphanRemoval() default false;

    // NEW: replaces automaticStore + cascadeDelete booleans
    CascadeType[] cascade() default {};

    // DEPRECATED but still functional for backward compatibility
    @Deprecated boolean automaticStore() default true;
    @Deprecated boolean cascadeDelete() default false;
}
```

### Backward compatibility

- `automaticStore=true` (current default) maps to `cascade={PERSIST}` if no `cascade` is specified
- `cascadeDelete=true` maps to `cascade` containing `REMOVE`
- If `cascade` is explicitly set, the deprecated booleans are ignored
- Migration path: deprecation warnings in log, remove booleans in next major version

### CASCADE REFRESH behavior

When `morphium.refresh(parent)` is called and a `@Reference` field has
`CascadeType.REFRESH`:
1. Refresh the parent entity (see TOP06)
2. For each `@Reference` field with REFRESH cascade:
   - If the referenced entity is loaded (not a lazy proxy), refresh it too
   - If it's a lazy proxy that hasn't been accessed, skip (nothing to refresh)
   - If it's a collection of references, refresh each loaded element

### CASCADE ALL

`CascadeType.ALL` is equivalent to `{PERSIST, REMOVE, REFRESH}`.

## Affected Files

- `annotations/CascadeType.java` — new enum
- `annotations/Reference.java` — add `cascade()` attribute, deprecate booleans
- `CascadeHelper.java` — adapt to use CascadeType enum
- `Morphium.java` — integrate REFRESH cascade with `refresh()` method
- `ObjectMapperImpl.java` — adapt automaticStore check to CascadeType
- Tests — extend `ReferenceCascadeTest.java`

## Acceptance Criteria

- [ ] `cascade={CascadeType.PERSIST}` behaves like `automaticStore=true`
- [ ] `cascade={CascadeType.REMOVE}` behaves like `cascadeDelete=true`
- [ ] `cascade={CascadeType.ALL}` enables all cascade operations
- [ ] `cascade={CascadeType.REFRESH}` refreshes referenced entities on parent refresh
- [ ] Deprecated boolean attributes still work (backward compatible)
- [ ] `orphanRemoval` remains a separate boolean (JPA-compatible)
- [ ] Works with List/Set of references
- [ ] Cycle detection (from existing CascadeHelper) still works
