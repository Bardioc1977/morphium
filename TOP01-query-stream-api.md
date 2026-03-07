# TOP 01 — Query Stream API

**Priority:** 1 (Quick Win)
**Effort:** Low (< 1 day)
**Impact:** High — modern Java API, functional pipelines

---

## Gap

JPA provides `query.getResultStream()` returning a `Stream<T>` for lazy, functional
processing of query results. Morphium only offers `asList()` (loads everything into
memory) and `asIterable()` (returns an `Iterator`, no Stream support).

## Current State in Morphium

```java
// Today: must collect into list first, then stream
List<User> users = query.asList();
users.stream().filter(...).map(...).collect(...);

// Or use iterator manually
QueryIterator<User> it = query.asIterable();
while (it.hasNext()) { ... }
```

## Proposed Design

Add a `stream()` method to `Query<T>` that wraps the existing `QueryIterator` into
a Java `Stream<T>` backed by the MongoDB cursor.

```java
// New API
query.stream().filter(u -> u.getAge() > 18).map(User::getName).collect(toList());

// With parallel hint
query.stream(500) // batch size
```

### Implementation sketch

```java
// In Query.java or QueryImpl.java
default Stream<T> stream() {
    QueryIterator<T> iterator = asIterable();
    Spliterator<T> spliterator = Spliterators.spliteratorUnknownSize(
        iterator, Spliterator.ORDERED | Spliterator.NONNULL);
    return StreamSupport.stream(spliterator, false)
        .onClose(iterator::close);
}
```

Key considerations:
- The stream MUST be closeable (cursor cleanup). Use `Stream.onClose()`.
- Document that streams should be used in try-with-resources.
- Optionally add `stream(int batchSize)` overload.

## Affected Files

- `Query.java` (interface) — add `stream()` default method
- `QueryImpl.java` — concrete implementation if needed
- `QueryIterator.java` — ensure `close()` is properly implemented (cursor cleanup)
- Tests — new test class `QueryStreamTest`

## Acceptance Criteria

- [ ] `query.stream()` returns a `Stream<T>` backed by MongoDB cursor
- [ ] Cursor is closed when stream is closed or fully consumed
- [ ] Works with `skip()`, `limit()`, `sort()`, `project()` query modifiers
- [ ] Works with InMemoryDriver
- [ ] No memory overhead vs. iterator approach
