# PR #151 Follow-up: QueryHelper Map-Handling Short-Circuit Fix

## Context

PR #151 (`fix: QueryHelper.matchesQuery short-circuits on first field in multi-field queries`)
is open on `sboesebeck/morphium` with branch `Bardioc1977:pr/fix-queryhelper-multifield`.

The maintainer merged our `matchesFieldCondition` extraction fix but asks us to **also fix
the Map-handling section** at the top of `matchesQuery()` (lines 128-221 on `origin/develop`),
which has the same short-circuit bug: `return true` exits the entire method after the first
matching Map field, skipping remaining query keys.

PR #152 (MongoField.not() wrong query structure) was merged into `origin/develop`, so our
branch needs rebasing.

## Bug Description

In `QueryHelper.matchesQuery()`, the Map-handling pre-loop (lines 128-221) checks whether
query fields are exact Map matches or array-index matches. When a match is found, it
immediately `return true`s from the method. This means that if a multi-field query has
e.g. `{mapField: {key: "val"}, count: {$gt: 10}}` and the document's `mapField` matches,
the `count` condition is **never checked**.

**Two `return true` statements cause this:**

1. **Line 147** (Map-Map matching): Document field and query value are both Maps, all
   key-value pairs match -> `return true`
2. **Line 217** (Map-List / array-index matching): Query has Map, document has List,
   all array index checks pass -> `return true`

### Reproduction

```java
// Document has a Map field AND a numeric field
Map<String, Object> doc = Doc.of(
    "metadata", Doc.of("type", "invoice"),
    "amount", 50
);
// Query: metadata matches AND amount > 100 (should be FALSE)
Map<String, Object> query = Doc.of(
    "metadata", Doc.of("type", "invoice"),
    "amount", Doc.of("$gt", 100)
);
// BUG: returns true because metadata map-matches first, amount never checked
QueryHelper.matchesQuery(query, doc, null); // true (WRONG, should be false)
```

## Fix

### Strategy: `consumedKeys` Set

Track which query keys were successfully handled by the Map-matching section.
In the main loop, skip those keys (they already matched). Only if ALL keys pass
(consumed + main-loop) does the method return true.

### File: `morphium-core/src/main/java/de/caluga/morphium/driver/inmem/QueryHelper.java`

#### Step 1: Add consumedKeys before the Map-handling loop

Before line 129 (`for (String key : query.keySet())`), add:

```java
Set<String> consumedKeys = new HashSet<>();
```

(The `Set` and `HashSet` imports already exist in the file.)

#### Step 2: Replace `return true` at line 147

Change:
```java
if (allMatched) {
    return true;
}
```
To:
```java
if (allMatched) {
    consumedKeys.add(key);
}
```

#### Step 3: Replace `return true` at line 217

Same change:
```java
if (allMatched) {
    return true;
}
```
To:
```java
if (allMatched) {
    consumedKeys.add(key);
}
```

#### Step 4: Skip consumed keys in the main loop

In the main loop (starts at `for (String keyQuery : query.keySet())`), add at the
very top of the loop body, BEFORE the `switch (keyQuery)`:

```java
if (consumedKeys.contains(keyQuery)) {
    ret = true;
    continue;
}
```

### Important: Also re-apply the `matchesFieldCondition` extraction from original PR

The original PR #151 extracted the field-condition logic from the `default:` case of the
main switch into a separate `matchesFieldCondition()` method. This fix is NOT yet on
`origin/develop` and must be re-applied on the fresh branch. The extraction ensures that
early `return` statements in the field-condition code exit `matchesFieldCondition()` (not
`matchesQuery()`), so the outer loop continues to check remaining fields.

**What to extract:** In the `default:` case of the `switch (keyQuery)`, everything from
`// field check` through the end of the large `if/else` block. Wrap it in:

```java
/**
 * Evaluates a single field condition within a query document.
 * Extracted from matchesQuery so that multi-field queries correctly AND
 * all field conditions (previously, early returns in this block would
 * short-circuit the outer loop, skipping remaining fields).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
private static boolean matchesFieldCondition(String keyQuery,
                                              Map<String, Object> query,
                                              Map<String, Object> toCheck,
                                              Map<String, Object> collation) {
    // ... extracted field-check code ...
}
```

And in the `default:` case, replace the extracted code with:
```java
default:
    if (keyQuery.startsWith("$") && !isKnownOperator(keyQuery)) {
        throw new IllegalArgumentException("unknown top level operator: " + keyQuery);
    }
    if (!matchesFieldCondition(keyQuery, query, toCheck, collation)) {
        return false;
    }
    ret = true;
    continue;
```

Inside the extracted method, change:
- `ret = true; continue;` -> `return true;` (since we're now in a method, not a loop)
- Any `return true/false` that was a loop-level return stays as-is (they now exit the method correctly)

### File: `morphium-core/src/test/java/de/caluga/test/mongo/suite/inmem/QueryHelperTest.java`

Add these 4 test methods to the existing `QueryHelperTest` class:

```java
@Test
public void mapFieldPlusOperatorBothPass() {
    // Map field matches AND operator condition matches -> true
    Map<String, Object> doc = Doc.of(
            "metadata", Doc.of("type", "invoice", "region", "EU"),
            "amount", 250);
    Map<String, Object> query = Doc.of(
            "metadata", Doc.of("type", "invoice"),
            "amount", Doc.of("$gt", 100));
    assertTrue(QueryHelper.matchesQuery(query, doc, null));
}

@Test
public void mapFieldPassesButOperatorFails() {
    // Map field matches but operator condition fails -> false
    // This is the core regression: previously returned true after map match
    Map<String, Object> doc = Doc.of(
            "metadata", Doc.of("type", "invoice", "region", "EU"),
            "amount", 50);
    Map<String, Object> query = Doc.of(
            "metadata", Doc.of("type", "invoice"),
            "amount", Doc.of("$gt", 100));
    assertFalse(QueryHelper.matchesQuery(query, doc, null),
            "Map match must not short-circuit; amount 50 is NOT > 100");
}

@Test
public void mapFieldFailsOperatorPasses() {
    // Map field does NOT match, operator condition matches -> false
    Map<String, Object> doc = Doc.of(
            "metadata", Doc.of("type", "receipt"),
            "amount", 250);
    Map<String, Object> query = Doc.of(
            "metadata", Doc.of("type", "invoice"),
            "amount", Doc.of("$gt", 100));
    assertFalse(QueryHelper.matchesQuery(query, doc, null));
}

@Test
public void arrayIndexPlusOperatorCondition() {
    // Array index matches but additional field fails -> false
    Map<String, Object> doc = Doc.of(
            "items", List.of(Doc.of("name", "Widget")),
            "status", "DRAFT");
    // items.0.name == "Widget" (match) AND status == "PUBLISHED" (fail)
    Map<String, Object> query = Doc.of(
            "items", Doc.of("0", Doc.of("name", "Widget")),
            "status", "PUBLISHED");
    assertFalse(QueryHelper.matchesQuery(query, doc, null),
            "Array index match must not short-circuit; status mismatch");
}
```

Required import (if not already present):
```java
import de.caluga.morphium.driver.Doc;
```

## Git Workflow

**IMPORTANT:** The existing `pr/fix-queryhelper-multifield` branch contains unrelated changes
that will cause messy rebase conflicts (it reverts Expr.java `cond`, changes MongoFieldImpl's
`add()` method differently from merged PR #152, and removes `notRegexQueryTest` from
RawQueryTests). The cleanest approach is to **recreate the branch** from `origin/develop`.

```bash
cd /Volumes/Entwicklung/workspaces/porsche/morphium-workspace/morphium

# 1. Fetch latest origin/develop (includes merged PR #152)
git fetch origin

# 2. Delete the old branch locally
git checkout develop
git branch -D pr/fix-queryhelper-multifield

# 3. Create fresh branch from origin/develop
git checkout -b pr/fix-queryhelper-multifield origin/develop

# 4. Implement ALL changes:
#    a) The matchesFieldCondition extraction (from original PR #151 — re-apply manually)
#    b) The consumedKeys fix (Steps 1-4 above)
#    c) The 4 new QueryHelperTest methods
#
#    The matchesFieldCondition extraction means: extract the `default:` case body
#    in the main switch (everything from `// field check` to the closing `}` of the
#    else-block) into a private static method `matchesFieldCondition(keyQuery, query,
#    toCheck, collation)` that returns boolean. In the switch default, call it:
#      if (!matchesFieldCondition(keyQuery, query, toCheck, collation)) {
#          return false;
#      }
#      ret = true;
#      continue;

# 5. Verify
mvn -pl morphium-core test -Dtest="de.caluga.test.mongo.suite.inmem.QueryHelperTest" -DfailIfNoTests=false

# 6. Force-push (replaces old branch on fork)
git push fork pr/fix-queryhelper-multifield --force-with-lease
```

## Verification

```bash
# Targeted test
mvn -pl morphium-core test \
    -Dtest="de.caluga.test.mongo.suite.inmem.QueryHelperTest" \
    -DfailIfNoTests=false

# Full inmemory regression
./runtests.sh --tags inmemory --skip
```

## PR Comment (draft, present to user before sending)

After implementation and green tests, prepare a comment on PR #151 (do NOT send
without user approval):

> Hi Stephan,
>
> Good catch on the Map-handling section! I've updated the PR to also fix the
> `return true` short-circuit in the Map/array-index pre-loop (lines 128-221).
>
> The fix uses a `consumedKeys` Set: when a query key is successfully matched by
> the Map-handling section, it's added to `consumedKeys` instead of immediately
> returning true. The main loop then skips consumed keys. This ensures all query
> conditions are checked via AND semantics, regardless of whether they're handled
> by Map matching or operator evaluation.
>
> Added 4 new tests covering Map+operator combinations (both pass, map-only pass,
> map fail, and array-index + operator).
>
> The branch is rebased onto current develop (includes merged #152).
>
> Cheers!
