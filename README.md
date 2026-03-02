# Morphium 6.2.0-SNAPSHOT

**Feature-rich MongoDB ODM and messaging framework for Java 21+**

Available languages: English and [Deutsch](README.de.md)

- 🗄️ **High-performance object mapping** with annotation-driven configuration
- 📨 **Integrated message queue** backed by MongoDB (no extra infrastructure)
- ⚡ **Multi-level caching** with cluster-wide invalidation
- 🔌 **Custom MongoDB wire-protocol driver** tuned for Morphium
- 🧪 **In-memory driver** for fast tests (no MongoDB required)
- 🎯 **JMS API (experimental)** for standards-based messaging
- 🚀 **Java 21** with virtual threads for optimal concurrency

[![Maven Central](https://img.shields.io/maven-central/v/de.caluga/morphium.svg)](https://search.maven.org/artifact/de.caluga/morphium)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## 🎯 Why Morphium?

Morphium is the only Java ODM that ships a message queue living inside MongoDB. If you already run MongoDB, you can power persistence, messaging, caching, and change streams with a single component.

| Feature | Morphium | Spring Data + RabbitMQ | Kafka |
|---------|----------|------------------------|-------|
| Infrastructure | MongoDB only | MongoDB + RabbitMQ | MongoDB + Kafka |
| Setup complexity | ⭐ Very low | ⭐⭐⭐ Medium | ⭐⭐⭐⭐⭐ High |
| Message persistence | Built in | Optional | Built in |
| Message priority | ✅ Yes | ✅ Yes | ❌ No |
| Distributed locks | ✅ Yes | ❌ No | ❌ No |
| Throughput (internal tests) | ~8K msg/s | 10K–50K msg/s | 100K+ msg/s |
| Operations | ⭐ Very easy | ⭐⭐ Medium | ⭐⭐⭐⭐ Complex |

_* Numbers are indicative and depend heavily on hardware and workload._

## 📚 Documentation

### Quick access
- **[Documentation hub](docs/index.md)** – entry point for all guides
- **[Overview](docs/overview.md)** – core concepts, quick start, compatibility
- **[Migration v5→v6](docs/howtos/migration-v5-to-v6.md)** – step-by-step upgrade guide
- **[InMemory Driver Guide](docs/howtos/inmemory-driver.md)** – capabilities, caveats, testing tips
- **[Optimistic Locking (`@Version`)](docs/howtos/optimistic-locking.md)** – prevent lost updates with `@Version`
- **[SSL/TLS & MONGODB-X509](docs/ssl-tls.md)** – encrypted connections and certificate-based authentication

### More resources
- Aggregation examples: `docs/howtos/aggregation-examples.md`
- Messaging implementations: `docs/howtos/messaging-implementations.md`
- Performance guide: `docs/performance-scalability-guide.md`
- Production deployment: `docs/production-deployment-guide.md`
- Monitoring & troubleshooting: `docs/monitoring-metrics-guide.md`

## 🚀 What’s New in v6.2

- **`@AutoSequence`** — Zero-boilerplate sequence number assignment on `store()` / `storeList()` with O(1) batch allocation
- **Azure CosmosDB** — Automatic detection via `hello` handshake with built-in compatibility guards ([details](docs/cosmosdb-compatibility.md))
- **`BackendType` enum** — `morphium.getBackendType()` returns MONGODB, COSMOSDB, MORPHIUM_SERVER, or IN_MEMORY
- **`MorphiumDriverException` unchecked** — Extends `RuntimeException`, eliminates 40+ catch-wrap blocks
- **Standalone MongoDB** — WriteConcern auto-downgrade and transaction warnings
- **DNS SRV** — Pure-Java `DnsSrvResolver` for `mongodb+srv://` (no JNDI)
- **Quarkus** — Thread context classloader support in all `Class.forName()` calls

See [CHANGELOG](CHANGELOG.md) for full details.

<details>
<summary>Previous versions (6.0 – 6.1)</summary>

### v6.1.x
- **MONGODB-X509** client-certificate authentication
- **`@Version`** optimistic locking — see `docs/howtos/optimistic-locking.md`
- **MorphiumServer** as drop-in MongoDB replacement (wire protocol, CLI, replica set emulation, persistence)

### v6.0
- **Java 21** with virtual threads
- **SSL/TLS** for secure connections
- **In-memory driver** with ~93% MongoDB feature coverage
- **Messaging** improvements (fewer duplicates, distributed locking, higher throughput)
- Complete documentation overhaul
</details>

## ✅ Requirements
- Java 21 or newer
- MongoDB 5.0+ for production deployments
- Maven

Maven dependencies:
```xml
<dependency>
  <groupId>de.caluga</groupId>
  <artifactId>morphium</artifactId>
  <version>[6.2.0-SNAPSHOT,)</version>
</dependency>
<dependency>
  <groupId>org.mongodb</groupId>
  <artifactId>bson</artifactId>
  <version>4.11.5</version>
</dependency>
```

Migrating from v5? → `docs/howtos/migration-v5-to-v6.md`

## ⚡ Quick Start

### Maven dependency

```xml
<dependency>
  <groupId>de.caluga</groupId>
  <artifactId>morphium</artifactId>
  <version>6.2.0-SNAPSHOT</version>
</dependency>
```

### Object mapping example

```java
import de.caluga.morphium.Morphium;
import de.caluga.morphium.MorphiumConfig;
import de.caluga.morphium.annotations.*;
import de.caluga.morphium.driver.MorphiumId;
import java.time.LocalDateTime;
import java.util.List;

// Entity definition
@Entity
public class User {
    @Id
    private MorphiumId id;
    private String name;
    private String email;
    private LocalDateTime createdAt;
    // getters/setters
}

// Configuration
MorphiumConfig cfg = new MorphiumConfig();
cfg.connectionSettings().setDatabase("myapp");
cfg.clusterSettings().addHostToSeed("localhost", 27017);
cfg.driverSettings().setDriverName("PooledDriver");

Morphium morphium = new Morphium(cfg);

// Store entity
User user = new User();
user.setName("John Doe");
user.setEmail("john@example.com");
user.setCreatedAt(LocalDateTime.now());
morphium.store(user);

// Query
List<User> users = morphium.createQueryFor(User.class)
    .f("email").matches(".*@example.com")
    .sort("createdAt")
    .asList();
```

### Messaging example

```java
import de.caluga.morphium.messaging.MorphiumMessaging;
import de.caluga.morphium.messaging.Msg;

// Messaging setup
MorphiumMessaging messaging = morphium.createMessaging();
messaging.setSenderId("my-app");
messaging.start();

// Send a message
Msg message = new Msg("orderQueue", "Process Order", "Order #12345");
message.setPriority(5);
message.setTtl(300000); // 5 minutes
messaging.sendMessage(message);

// Receive messages
messaging.addListenerForTopic("orderQueue", (m, msg) -> {
    // process order ...
    return null; // no reply
});
```

### Properties & environment configuration

```bash
# Environment variables
export MONGODB_URI='mongodb://user:pass@localhost:27017/app?replicaSet=rs0'
export MORPHIUM_DRIVER=inmem

# System properties
mvn -Dmorphium.uri='mongodb://localhost/mydb' test

# Properties file (morphium.properties)
morphium.hosts=mongo1.example.com:27017,mongo2.example.com:27017
morphium.database=myapp
morphium.replicaSet=myReplicaSet
```

## 🧪 Tests & Test Runner

### Maven
```bash
# All tests
mvn test

# Full build with checks
mvn clean verify

# Tagged test selection
mvn test -Dgroups="core,messaging"

# Run against a real MongoDB instance
mvn test -Dmorphium.driver=pooled -Dmorphium.uri=mongodb://localhost/testdb
```

### `./runtests.sh` helper
```bash
# Default: in-memory driver (fast, no MongoDB required)
./runtests.sh

# Run tagged suites
./runtests.sh --tags core,messaging

# Parallel runs
./runtests.sh --parallel 8 --tags core

# Retry only failed methods
./runtests.sh --rerunfailed
./runtests.sh --rerunfailed --retry 3

# Single test class
./runtests.sh CacheTests

# Statistics
./runtests.sh --stats
./getFailedTests.sh  # list failed methods
```

Run `./runtests.sh --help` to see every option.

### Multi-Backend Testing

Tests are parameterized to run against multiple drivers. Use `--driver` to select:

```bash
# InMemory only (fastest, default)
./runtests.sh --driver inmem

# Against external MongoDB with all drivers (pooled + single + inmem)
./runtests.sh --uri mongodb://mongo1,mongo2/testdb --driver all

# Against external MongoDB with pooled driver only
./runtests.sh --uri mongodb://mongo1,mongo2/testdb --driver pooled

# Against MorphiumServer (auto-starts local server)
./runtests.sh --morphium-server --driver pooled
```

**Complete test coverage** requires running against all backends:
```bash
# 1. Fast in-memory tests
./runtests.sh --driver inmem

# 2. Real MongoDB tests
./runtests.sh --uri mongodb://your-mongodb/testdb --driver all

# 3. MorphiumServer tests
./runtests.sh --morphium-server --driver pooled
```

**New in v6.1**
- ✅ **Unified test base**: All tests now use `MultiDriverTestBase` with parameterized drivers
- ✅ **Driver selection**: Each test declares which drivers it supports via `@MethodSource`
- ✅ **Parallel safe**: Tests isolated per parallel slot with unique databases

**New in v6.0**
- ✅ **Method-level reruns**: `--rerunfailed` only re-executes failing methods
- ✅ **No more hangs**: known deadlocks resolved
- ✅ **Faster iteration**: noticeably quicker partial retries
- ✅ **Better filtering**: class-name filters now reliable

Run `./runtests.sh --help` to see every option.

### Test configuration precedence

`TestConfig` consolidates all test settings. Priority order:
1. System properties (`-Dmorphium.*`)
2. Environment variables (`MORPHIUM_*`, `MONGODB_URI`)
3. `src/test/resources/morphium-test.properties`
4. Defaults (localhost:27017)

## 🔧 MorphiumServer & InMemoryDriver

### InMemoryDriver – MongoDB-free testing

The in-memory driver provides a largely MongoDB-compatible data store fully in memory:

**Features**
- ✅ Full CRUD operations
- ✅ Rich query operator coverage
- ✅ Aggregation stages such as `$match`, `$group`, `$project`
- ✅ Single-instance transactions
- ✅ Basic change streams
- ✅ JavaScript `$where` support

**Performance**
- Significantly faster than external MongoDB for tests
- No network latency
- No disk I/O
- Ideal for CI/CD pipelines

**Usage**
```bash
# All tests with the in-memory driver
./runtests.sh --driver inmem

# Specific tests
mvn test -Dmorphium.driver=inmem -Dtest="CacheTests"
```

See `docs/howtos/inmemory-driver.md` for feature coverage and limitations.

### MorphiumServer – Standalone MongoDB replacement

MorphiumServer runs the Morphium wire-protocol driver in a separate process, allowing it to act as a lightweight, in-memory MongoDB replacement.

**Building the Server**

First, build the project using Maven. This will generate the executable JAR in the `target/` directory.

```bash
mvn clean package -DskipTests
```

This creates `target/morphium-6.2.0-SNAPSHOT-server-cli.jar` (where 6.2.0-SNAPSHOT is the current version).

**Running the Server**

You can run the server directly from the command line:

```bash
# Start the server on the default port (17017)
java -jar target/morphium-6.2.0-SNAPSHOT-server-cli.jar

# Start on a different port
java -jar target/morphium-6.2.0-SNAPSHOT-server-cli.jar --port 8080

# Start with persistence (snapshots)
java -jar target/morphium-6.2.0-SNAPSHOT-server-cli.jar --dump-dir ./data --dump-interval 300
```

**Replica Set Support (Experimental)**

MorphiumServer supports basic replica set emulation. Start multiple instances with the same replica set name and seed list:

```bash
java -jar target/morphium-6.2.0-SNAPSHOT-server-cli.jar --rs-name my-rs --rs-seed host1:17017,host2:17018
```

**Use cases**
- Local development without installing MongoDB
- CI environments
- Embedded database for desktop applications
- Smoke-testing MongoDB tooling (mongosh, Compass, mongodump, …)

**Current limitations**
- No sharding support
- Some advanced aggregation operators and joins still missing

See `docs/morphium-server.md` for more details on persistence and replica sets.

## 🚀 Production Use Cases

Organizations run Morphium in production for:
- **E-commerce**: order processing with guaranteed delivery
- **Financial services**: coordinating transactions across microservices
- **Healthcare**: patient-data workflows with strict compliance
- **IoT platforms**: device state synchronization and command distribution
- **Content management**: document workflows and event notifications

## 🤝 Community & Contribution

### Stay in touch
- **Slack**: [Team Morphium](https://join.slack.com/t/team-morphium/shared_invite/enQtMjgwODMzMzEzMTU5LTA1MjdmZmM5YTM3NjRmZTE2ZGE4NDllYTA0NTUzYjU2MzkxZTJhODlmZGQ2MThjMGY0NmRkMWE1NDE2YmQxYjI)
- **Blog**: https://caluga.de
- **GitHub**: [sboesebeck/morphium](https://github.com/sboesebeck/morphium)
- **Issues**: Report bugs or request features on GitHub

### Contributing

We appreciate pull requests! Areas where help is especially welcome:
- **InMemoryDriver**: expanding MongoDB feature coverage
- **Documentation**: tutorials, examples, translations
- **Performance**: profiling and benchmarks
- **Tests**: broader scenarios and regression coverage

**How to contribute**
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add AmazingFeature'`)
4. Push the branch (`git push origin feature/AmazingFeature`)
5. Open a pull request

**Tips**
- Respect test tags (`@Tag("inmemory")`, `@Tag("external")`)
- Run `./runtests.sh --tags core` before submitting
- Update documentation when you change APIs

## 📜 License

Apache License 2.0 – see [LICENSE](LICENSE) for details.

## 🙏 Thanks

Thanks to every contributor who helped ship Morphium 6.2.0-SNAPSHOT and to the MongoDB community for continuous feedback.

---

**Questions?** Open an issue on [GitHub](https://github.com/sboesebeck/morphium/issues) or browse the [documentation](docs/index.md).

**Planning an upgrade?** Follow the [migration guide](docs/howtos/migration-v5-to-v6.md).

Enjoy Morphium 6.2.0-SNAPSHOT! 🚀

*Stephan Bösebeck & the Morphium team*
