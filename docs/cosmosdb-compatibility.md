# Azure CosmosDB Compatibility

Morphium supports Azure CosmosDB for MongoDB API with **automatic backend detection** and **built-in compatibility guards**. No manual configuration is required — Morphium detects CosmosDB at connect time and adjusts its behavior automatically.

---

## Quick Start

```java
MorphiumConfig cfg = new MorphiumConfig();
cfg.connectionSettings().setDatabase("myapp");
cfg.connectionSettings().setUseSSL(true); // required for CosmosDB
cfg.clusterSettings().addHostToSeed("myaccount.mongo.cosmos.azure.com", 10255);
cfg.authSettings().setMongoLogin("myaccount");
cfg.authSettings().setMongoPassword("your-primary-key");

Morphium morphium = new Morphium(cfg);

// Detection is automatic
BackendType backend = morphium.getBackendType(); // → BackendType.COSMOSDB
```

That's it. Morphium detects the CosmosDB backend during the `hello` handshake and enables compatibility guards automatically.

---

## Automatic Backend Detection

### How It Works

During the initial `hello` handshake, Morphium inspects three signals to identify CosmosDB:

| Signal | Detection Method | Confidence |
|---|---|---|
| **Hostname** | `*.mongo.cosmos.azure.com` or `*.mongocluster.cosmos.azure.com` (vCore) | High |
| **Seed Hosts** | Any configured seed host matches the hostname patterns above | High |
| **Handshake** | `setName == "globaldb"` combined with SSL enabled | Medium (fallback) |

Detection happens in the driver layer (`PooledDriver`, `SingleMongoConnectDriver`) and is exposed via the `MorphiumDriver` interface:

```java
// Check if connected to CosmosDB
boolean cosmos = morphium.getDriver().isCosmosDB();

// Get the full backend type
BackendType type = morphium.getBackendType();
// → MONGODB, COSMOSDB, MORPHIUM_SERVER, or IN_MEMORY
```

### BackendType Enum

```java
public enum BackendType {
    MONGODB,           // Standard MongoDB (standalone, replica set, sharded)
    COSMOSDB,          // Azure CosmosDB for MongoDB API
    MORPHIUM_SERVER,   // MorphiumServer (Netty-based, wire-protocol-compatible)
    IN_MEMORY          // InMemoryDriver (unit tests)
}
```

The `getBackendType()` method derives the enum from the individual detection flags:
1. `isInMemoryBackend()` → `IN_MEMORY`
2. `isCosmosDB()` → `COSMOSDB`
3. `isMorphiumServer()` → `MORPHIUM_SERVER`
4. Otherwise → `MONGODB`

---

## Compatibility Guards

When CosmosDB is detected, Morphium automatically guards against unsupported operations. No code changes are needed for basic usage — the guards protect you at runtime.

### Behavior Matrix

| Operation | MongoDB | CosmosDB | Guard Behavior |
|---|---|---|---|
| CRUD (`store`/`delete`/`query`) | Full | Full | No guard needed |
| Query Builder (`Query<T>`) | Full | Full | No guard needed |
| Aggregation (basic) | Full | Full | No guard needed |
| Indexes (single, compound, 2dsphere) | Full | Full | No guard needed |
| TTL Indexes / `@CreationTime` | Full | Full | No guard needed |
| SSL/TLS | Optional | **Required** | — |
| Caching (`@Cache`) | Full | Full | Client-side, no server dependency |
| **Transactions** | Full | Not supported | `UnsupportedOperationException` |
| **MapReduce** | Full | Not supported | `UnsupportedOperationException` |
| **Capped Collections** (`@Capped`) | Full | Not supported | Silently creates regular collection |
| **Change Streams** | Full | Limited (no delete events) | Warning logged |
| `$merge` / `$out` | Full | Not supported | Application-level (no guard) |
| `$lookup` with pipeline | Full | Limited | Application-level (no guard) |
| Text Indexes / `$text` | Full | Not supported | Application-level (no guard) |

### Guard Details

#### Transactions → `UnsupportedOperationException`

```java
morphium.beginTransaction();
// → throws UnsupportedOperationException:
//    "Transactions are not supported on Azure CosmosDB.
//     Use atomic operations (inc/dec/set) for single-document consistency."
```

Individual document operations (`inc()`, `dec()`, `set()`, `store()`) remain atomic on CosmosDB. Only multi-document transactions are blocked.

#### MapReduce → `UnsupportedOperationException`

```java
morphium.mapReduce(MyEntity.class, mapFn, reduceFn);
// → throws UnsupportedOperationException:
//    "MapReduce is not supported on CosmosDB.
//     Use the Aggregation framework: morphium.createAggregator()"
```

Use the Aggregation framework as a replacement.

#### Capped Collections → Silent Degradation

```java
@Entity @Capped(maxSize = 100000, maxEntries = 1000)
public class AuditLog { ... }

morphium.ensureIndicesFor(AuditLog.class);
// On CosmosDB: creates a regular collection (no capped, no max size/entries)
// Logs: "CosmosDB: @Capped annotation ignored, creating regular collection"
```

The `checkCapped()` method returns an empty map on CosmosDB instead of querying collection stats.

#### Change Streams → Warning

```java
morphium.watch(MyEntity.class, true, pipeline, callback);
// On CosmosDB: proceeds normally but logs a warning:
//    "CosmosDB: Change streams have limited support.
//     Delete events may not be received."
```

For MorphiumMessaging, use polling mode on CosmosDB:

```java
messaging.setPolling(true);
messaging.setUseChangeStream(false);
```

---

## Wire Protocol Compatibility

### OP_MSG (MongoDB 3.6+)

Morphium uses **exclusively OP_MSG (Opcode 2013)** for all communication. Legacy opcodes are not used.

- **Handshake**: `hello` command (MongoDB 5.0+), no `isMaster` fallback
- **Compression**: Snappy + Zlib (ZSTD not supported)
- **Authentication**: SCRAM-SHA-256, SCRAM-SHA-1, X.509
- **DNS SRV**: Custom `DnsSrvResolver` for `mongodb+srv://`

**Requirement: CosmosDB MongoDB API 5.0 or later.** The `hello` command is not available in API 4.0.

### HelloResult Parsing

Morphium parses the `hello` response via reflection into `HelloResult`. Critical fields:

| Field | Usage | CosmosDB Risk |
|---|---|---|
| `maxWireVersion` / `minWireVersion` | Protocol negotiation | Low |
| `isWritablePrimary` | Write routing | Low |
| `hosts` / `setName` | Replica set topology | Medium — CosmosDB uses `globaldb` |
| `logicalSessionTimeoutMinutes` | Session management | Low — not used without transactions |
| `compression` | Compression negotiation | Low |
| `helloOk` | Confirms `hello` support | Low |

### Relevant Source Files

| File | Description |
|---|---|
| `driver/BackendType.java` | Backend type enum |
| `driver/MorphiumDriver.java` | `isCosmosDB()` and `getBackendType()` default methods |
| `driver/wire/HelloResult.java` | Parses `hello` response (incl. `cosmosDB` field) |
| `driver/wire/PooledDriver.java` | Detection logic in `handleHelloResult()` |
| `driver/wire/SingleMongoConnectDriver.java` | Detection logic in `connect()` |
| `Morphium.java` | Compatibility guards + `getBackendType()` convenience |

---

## Local Testing

### CosmosDB Emulator: Not Available

Microsoft does not provide a Docker emulator with MongoDB wire protocol support. The `azure-cosmos-emulator:vnext-preview` image only supports the NoSQL API.

### Recommended Test Strategy

1. **InMemoryDriver** — Unit tests (fastest, no infrastructure)
2. **MongoDB 5.0 Docker** — Integration tests, validates wire protocol
3. **Azure CosmosDB Free Tier** — End-to-end compatibility validation

### Testing CosmosDB Guards Locally

The compatibility guards can be tested without a real CosmosDB instance. The test suite uses a dynamic proxy to override `isCosmosDB()` on the InMemoryDriver:

```java
// Example from CosmosDBCompatibilityTest.java
MorphiumDriver cosmosProxy = (MorphiumDriver) Proxy.newProxyInstance(
    driver.getClass().getClassLoader(),
    new Class[]{MorphiumDriver.class},
    (proxy, method, args) -> {
        if ("isCosmosDB".equals(method.getName())) return true;
        if ("getBackendType".equals(method.getName())) return BackendType.COSMOSDB;
        return method.invoke(driver, args);
    }
);
morphium.setDriver(cosmosProxy);

// Now all guards are active
assertThrows(UnsupportedOperationException.class, () -> morphium.beginTransaction());
```

---

## Feature Compatibility Details

### Server Sessions (`lsid`)

Morphium sends `lsid` **only during active transactions**, not with every command:

```java
// Only when transaction is active:
if (driver.getTransactionContext() != null) {
    q.getFirstDoc().put("lsid", Doc.of("id", driver.getTransactionContext().getLsid()));
    q.getFirstDoc().put("txnNumber", driver.getTransactionContext().getTxnNumber());
}
```

Normal CRUD without transactions is **not** affected by session limitations.

### MorphiumMessaging

Messaging uses `@Entity` (not `@Capped`) with a TTL index. The architecture:
- **Primary**: Change Streams (two monitors: lock collection + message collection)
- **Fallback**: Polling (`setPolling(true)` / `setUseChangeStream(false)`)

On CosmosDB, change stream delete events may not arrive. Use polling mode:

```java
MorphiumMessaging messaging = new MorphiumMessaging(morphium, 100, true);
messaging.setPolling(true);
messaging.setUseChangeStream(false);
messaging.start();
```

### `$merge` / `$out` Aggregation Stages

CosmosDB does not support `$merge` or `$out`. These are used by `AggregatorImpl` for aggregation output. No automatic guard exists — avoid these stages in your aggregation pipelines when targeting CosmosDB.

---

## Summary

| Question | Answer |
|---|---|
| Is Morphium compatible with CosmosDB? | **Yes** — with automatic compatibility guards for unsupported features |
| Is manual configuration needed? | **No** — detection is automatic via `hello` handshake |
| What wire protocol version is required? | CosmosDB MongoDB API **5.0+** (for `hello` command support) |
| What doesn't work? | Transactions, MapReduce, capped collections, `$merge`/`$out` |
| How to test locally? | InMemoryDriver + dynamic proxy for guard testing |
| What about Messaging? | Use polling mode (`setPolling(true)`) on CosmosDB |

### Risk Matrix

| Level | Features |
|---|---|
| **Works** | CRUD, Query Builder, Aggregation, Indexes, TTL, Caching, SSL/TLS |
| **Works with configuration** | Messaging (polling mode instead of change streams) |
| **Guarded (auto)** | Transactions (throws), MapReduce (throws), Capped (degrades), Change Streams (warns) |
| **Not supported** | `$merge`/`$out`, Text Search, `$lookup` with pipeline |
