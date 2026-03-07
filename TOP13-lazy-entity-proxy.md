# TOP 13 — getReference() — Lazy Entity Proxy Without DB Hit

**Priority:** 13
**Effort:** Medium (3-5 days)
**Impact:** Low-Medium — avoids unnecessary DB loads when setting references

---

## Gap

JPA's `em.getReference(MyEntity.class, id)` returns a proxy object that only
triggers a database load when a non-ID field is accessed. This allows setting
references without loading the referenced entity.

Morphium has `LazyDeReferencingProxy` for `@Reference(lazyLoading=true)` fields,
but no general-purpose lazy entity proxy that can be created on demand.

## Current State in Morphium

```java
// Must load the full customer just to set a reference
Customer customer = morphium.findById(Customer.class, customerId);
order.setCustomer(customer);
morphium.store(order);
// The findById was unnecessary — only the ID is stored in the reference
```

## Proposed Design

### API

```java
// New method on Morphium
public <T> T getReference(Class<T> type, Object id);
```

### Semantics

1. Returns a proxy instance of `T` with only the `@Id` field populated
2. No database query is executed at creation time
3. Accessing the `@Id` field returns the provided ID (no DB hit)
4. Accessing any other field triggers a transparent `findById()` to load all fields
5. After first access, the entity behaves like a normal loaded entity
6. `morphium.getId(proxy)` returns the ID without triggering a load
7. Passing the proxy to `store()` (as a reference) works — only the ID is stored

### Usage

```java
// No DB hit — creates a lightweight proxy
Customer ref = morphium.getReference(Customer.class, customerId);

order.setCustomer(ref);      // still no DB hit
morphium.store(order);       // stores only the customer ID in the reference field

// DB hit happens only if you access a non-ID field
String name = ref.getName(); // NOW the customer is loaded from DB
```

### Implementation approach

**Option A: ByteBuddy/cglib proxy**
```java
T proxy = new ByteBuddy()
    .subclass(type)
    .method(not(isDeclaredBy(Object.class)))
    .intercept(MethodDelegation.to(new LazyLoadInterceptor<>(morphium, type, id)))
    .make()
    .load(type.getClassLoader())
    .getLoaded()
    .getDeclaredConstructor()
    .newInstance();
```

**Option B: Extend existing `LazyDeReferencingProxy`**
Morphium already has a proxy mechanism for `@Reference(lazyLoading=true)`.
Generalize it for standalone use.

**Option B is preferred** — reuses existing infrastructure, less new dependencies.

### Edge cases

- `getReference()` for an ID that doesn't exist in DB → loads null on first access → throw `EntityNotFoundException`
- Serialization: proxy should serialize to a normal document (trigger load)
- `equals()`/`hashCode()`: should be based on ID (no load needed)
- `toString()`: should mention it's a proxy (no load) or trigger load (configurable)

## Affected Files

- `Morphium.java` — add `getReference(Class<T>, Object)` method
- `LazyDeReferencingProxy.java` — generalize or extract base proxy class
- `ObjectMapperImpl.java` — detect proxy during serialization, extract ID without loading
- Tests — `LazyEntityProxyTest.java`

## Acceptance Criteria

- [ ] `getReference()` returns immediately without DB query
- [ ] ID access does not trigger load
- [ ] Non-ID field access triggers transparent load
- [ ] Proxy works as `@Reference` target (stores only ID)
- [ ] Non-existent entity detected on first access
- [ ] `equals()`/`hashCode()` based on ID (no load)
- [ ] Works with InMemoryDriver
