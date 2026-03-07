# TOP 12 — Persistence Context / Identity Map

**Priority:** 12
**Effort:** High (2+ weeks)
**Impact:** High — but changes fundamental Morphium semantics

---

## Gap

JPA guarantees object identity within a persistence context: two `find()` calls
for the same primary key return the same Java object instance. This enables
dirty-checking (automatic detection of changed fields) and prevents inconsistent
state when the same entity is loaded via different paths.

Morphium creates new object instances on every query. The cache stores serialized
documents, not object references. Two queries for the same entity return distinct
Java objects.

## Current State in Morphium

```java
User a = morphium.findById(User.class, id);
User b = morphium.findById(User.class, id);
assert a != b;           // TRUE — different instances!
assert a.equals(b);      // depends on equals() implementation

a.setName("changed");
morphium.store(a);       // must explicitly call store()
// b still has old name — no automatic sync
```

## What a Persistence Context Would Provide

```java
morphium.beginTransaction();

User a = morphium.findById(User.class, id);
User b = morphium.findById(User.class, id);
assert a == b;            // SAME instance — identity map

a.setName("changed");
morphium.commitTransaction();
// Dirty-checking detects the change, auto-stores
```

## Proposed Design

### Scope

A persistence context is scoped to a transaction (or a manually managed unit-of-work).

### Identity Map

```java
// ThreadLocal identity map, keyed by (Class, id)
ThreadLocal<Map<EntityKey, Object>> identityMap = new ThreadLocal<>();
```

### Lifecycle

1. `beginTransaction()` or `beginUnitOfWork()` creates a new identity map
2. Every `find`/`query` result is registered in the identity map
3. If an entity with the same key already exists, return the existing instance
4. `commitTransaction()`:
   a. For each entity in the identity map, compute diff against original state
   b. Store entities that have changed (dirty-checking)
   c. Clear the identity map
5. `rollback()` discards all changes and clears the identity map

### Dirty-Checking

On load, snapshot the entity state (deep copy of field values).
On commit, compare current state against snapshot.
Changed fields → `updateUsingFields()` (partial update).

### Opt-in behavior

This MUST be opt-in to avoid breaking existing code:

```java
MorphiumConfig config = new MorphiumConfig();
config.setPersistenceContextEnabled(true);  // default: false
```

Or transaction-scoped:
```java
morphium.beginTransaction(PersistenceContextType.MANAGED);  // with identity map
morphium.beginTransaction();  // default: no identity map (backward compatible)
```

## Risks and Considerations

- **Breaking change potential:** Code that relies on distinct instances would break
- **Memory:** Identity map holds all loaded entities in memory for the transaction duration
- **Stale data:** Long-running transactions may see outdated data
- **Complexity:** Dirty-checking for embedded documents, references, collections
- **Cache interaction:** How does the identity map interact with Morphium's existing cache?
- **Thread-safety:** Identity map is ThreadLocal — fine for classic threading, but needs
  care with reactive/virtual threads

## Alternative: Lightweight Unit of Work

Instead of a full persistence context, implement just:
- `morphium.beginUnitOfWork()` — starts tracking
- `morphium.registerManaged(entity)` — snapshot current state
- `morphium.commitUnitOfWork()` — dirty-check and store changed entities
- No identity map, no automatic registration from queries

This is simpler and less risky.

## Affected Files

- `PersistenceContext.java` — new class (identity map + snapshots)
- `EntityKey.java` — composite key (Class + ID)
- `Morphium.java` — integrate with find/query/store/transaction lifecycle
- `ObjectMapperImpl.java` — snapshot/diff logic
- `MorphiumConfig.java` — opt-in configuration
- Tests — `PersistenceContextTest.java`

## Acceptance Criteria

- [ ] Identity map guarantees object identity within transaction scope
- [ ] Dirty-checking detects field changes automatically
- [ ] `commitTransaction()` stores only changed entities
- [ ] `rollback()` discards all changes
- [ ] Opt-in — existing code without transactions is unaffected
- [ ] Memory-bounded (warn on large identity maps)
- [ ] Works with InMemoryDriver

## Recommendation

**Do not rush this.** This is the most impactful and risky change in this list.
Consider the lightweight Unit of Work alternative first. Discuss with Stephan
before implementation — this changes the programming model.
