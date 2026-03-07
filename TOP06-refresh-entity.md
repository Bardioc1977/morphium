# TOP 06 — refresh(entity) — Reload Entity State from DB

**Priority:** 6 (Quick Win)
**Effort:** Low (1 day)
**Impact:** Medium — essential for optimistic locking conflict resolution

---

## Gap

JPA provides `em.refresh(entity)` which reloads the entity's current database state
into the existing Java instance, overwriting any in-memory changes.

Morphium has `reread()` but it returns a new object instance. There's no in-place
refresh that updates the fields of the existing reference.

## Current State in Morphium

```java
User user = morphium.findById(User.class, id);
user.setName("changed locally");

// Want to reload from DB — but this gives a NEW instance
User fresh = morphium.reread(user);
// 'user' still has "changed locally"
// 'fresh' is a different object with DB state
```

This is problematic when other code holds references to the original `user` object.

## Proposed Design

```java
// New method on Morphium
public <T> void refresh(T entity);

// Overload with specific fields
public <T> void refresh(T entity, String... fields);
```

### Semantics

1. Extract `@Id` value from entity
2. Load current document from DB
3. Copy all persistent field values from loaded document into the existing instance
4. Update `@Version` field to current DB value
5. Fire `@PostLoad` lifecycle callbacks
6. If entity not found in DB, throw `EntityNotFoundException`

### Usage

```java
User user = morphium.findById(User.class, id);
user.setName("local change");

morphium.refresh(user);
// user.getName() now reflects DB state
// all references to 'user' see updated values
```

### Implementation sketch

```java
public <T> void refresh(T entity) {
    Object id = getId(entity);
    if (id == null) throw new IllegalArgumentException("Entity has no ID");
    Map<String, Object> doc = getDriver().findOneById(getCollectionName(entity), id);
    if (doc == null) throw new EntityNotFoundException("Entity not found: " + id);
    getMapper().updateEntityFromDocument(entity, doc);  // in-place field copy
    firePostLoad(entity);
}
```

The key new piece is `updateEntityFromDocument(entity, doc)` — similar to
`deserialize()` but writing into an existing instance instead of creating a new one.
Morphium's ObjectMapper already uses reflection to set fields, so this is mostly
a variant of the existing deserialization path.

## Affected Files

- `Morphium.java` — add `refresh(T entity)` method
- `ObjectMapperImpl.java` — add `updateEntityFromDocument()` or refactor `deserialize()` to accept a target instance
- Tests — `RefreshEntityTest.java`

## Acceptance Criteria

- [ ] `refresh(entity)` reloads all persistent fields from DB in-place
- [ ] `@Version` field is updated to current DB version
- [ ] `@PostLoad` callbacks are triggered
- [ ] Entity not found in DB throws clear exception
- [ ] Entity without `@Id` value throws clear exception
- [ ] Existing references to the entity see updated values
- [ ] Works with InMemoryDriver
