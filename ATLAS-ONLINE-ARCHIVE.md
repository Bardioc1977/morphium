# TOP14 — MongoDB Atlas Online Archive Integration

## Context

MongoDB Atlas Online Archive tiert selten genutzte Daten automatisch vom Atlas-Cluster (hot) in Cloud Object Storage (cold, z.B. Azure Blob/S3). Die archivierten Daten bleiben über einen separaten **Data Federation Endpoint** abfragbar — derselbe Endpoint kann hot+cold Daten in einem Query vereinen.

**Problem:** Der Federation-Endpoint ist ein anderer Host als der Cluster. Reads und Writes müssen getrennt geroutet werden:
- **Writes** → immer an Atlas-Cluster
- **Reads** → entweder Cluster-only (nur hot) oder Federation (hot+cold)
- Federation ist **read-only**, unterstützt **keine Transactions** und **keine Change Streams**
- Queries ohne Partition-Field-Filter verursachen Full-Archive-Scans ($5/TB, 10MB Minimum)

**Ziel:** Near-transparente Integration in Morphium über Annotation + Konfiguration.

---

## Architektur

```
                         Morphium
                        /        \
              getDriver()    getFederatedDriver()
                  |                |
            PooledDriver      ReadOnlyDriverWrapper(PooledDriver)
                  |                |
            Atlas Cluster    Data Federation Endpoint
            (read+write)     (read-only, hot+cold)
```

Zwei unabhängige `MorphiumDriver`-Instanzen. `Query.resolveDriverForRead()` entscheidet pro Query welcher Driver genutzt wird.

---

## 1. Konfiguration

**Neues File:** `src/main/java/de/caluga/morphium/config/OnlineArchiveSettings.java`

```java
public class OnlineArchiveSettings extends Settings {
    private String federationUrl = null;         // null = Feature disabled
    private String federationDatabase = null;    // null = same as cluster
    private boolean defaultFederatedReads = true;
    private CostGuardMode costGuardMode = CostGuardMode.WARN;  // WARN | STRICT | OFF
    private String provenanceFieldName = "_provenance";
    private int federationMaxConnections = 50;
    private int federationConnectionTimeout = 5000;
    private int federationReadTimeout = 30000;   // höher als Cluster (cold reads sind langsamer)
    private boolean federationShareSslSettings = true;
}
```

**Modify:** `MorphiumConfig.java` — neues Feld + Accessor, `isOnlineArchiveEnabled()` Convenience.

---

## 2. Annotation

**Neues File:** `src/main/java/de/caluga/morphium/annotations/OnlineArchive.java`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnlineArchive {
    DataSource defaultDataSource() default DataSource.AUTO;
    String[] partitionFields() default {};
    boolean trackProvenance() default true;
}
```

**Neues File:** `src/main/java/de/caluga/morphium/annotations/DataSource.java`

```java
public enum DataSource { CLUSTER, FEDERATED, AUTO }
```

**Usage:**
```java
@Entity
@OnlineArchive(partitionFields = {"created_date", "region"}, defaultDataSource = DataSource.FEDERATED)
public class AuditLog { ... }
```

---

## 3. Dual-Driver Lifecycle (Morphium.java)

**Modify:** `Morphium.java`

- Neues Feld: `private MorphiumDriver federatedDriver;`
- Neue Methoden: `getFederatedDriver()`, `isOnlineArchiveAvailable()`, `createFederatedQueryFor(Class)`
- In `initializeAndConnect()`: nach Cluster-Driver-Connect → `initializeFederatedDriver()` (zweiter PooledDriver, gewrapped in `ReadOnlyDriverWrapper`)
- In `close()`: Federation-Driver vor Cluster schließen

**Neues File:** `src/main/java/de/caluga/morphium/archive/ReadOnlyDriverWrapper.java`
- Delegiert alle Read-Methoden an den echten Driver
- `getPrimaryConnection()` → `UnsupportedOperationException`
- `startTransaction()` → `UnsupportedOperationException`

---

## 4. Query Routing (Kernstück)

**Modify:** `Query.java`

Neues Feld + Setter:
```java
private DataSource dataSourceOverride = null;
public Query<T> setDataSource(DataSource ds) { ... }
```

Neue Methode `resolveDriverForRead()`:
```
1. Kein Federation-Driver konfiguriert? → Cluster
2. Transaction aktiv? → Cluster (Federation hat keine Txn)
3. Expliziter Query-Override? → Nutze Override
4. @OnlineArchive Annotation auf Entity? → Nutze defaultDataSource
5. AUTO → Global-Default aus OnlineArchiveSettings
6. FEDERATED gewählt → Cost-Guard prüfen → Federation-Driver
7. Sonst → Cluster-Driver
```

**Änderung in `getFindCmd()`:**
```java
// Vorher:
con = getMorphium().getDriver().getReadConnection(getRP());
// Nachher:
con = resolveDriverForRead().getReadConnection(getRP());
```

Gleiche Änderung in: `count()`, `complexQueryCount()`, `explainCount()`.
`tail()` bleibt immer auf Cluster (keine Change Streams auf Federation).

**Modify:** `Aggregator.java` / `AggregatorImpl.java` — gleiche `DataSource`-Logik.

---

## 5. Cost Guard

**Neues File:** `src/main/java/de/caluga/morphium/archive/ArchiveCostGuard.java`

Prüft ob der Query-Filter die in `@OnlineArchive(partitionFields=...)` deklarierten Felder abdeckt. Durchsucht den Filter-Baum inkl. `$and`/`$or`.

- `WARN` Modus: Log-Warning mit fehlenden Partition-Feldern und Kostenhinweis
- `STRICT` Modus: wirft `ArchiveCostGuardException`
- `OFF`: kein Check

Wird in `resolveDriverForRead()` aufgerufen, bevor der Federation-Driver zurückgegeben wird.

---

## 6. Provenance Tracking

**Neues File:** `src/main/java/de/caluga/morphium/archive/DocumentProvenance.java`
```java
public enum DocumentProvenance { CLUSTER("primary"), ARCHIVE("secondary"), UNKNOWN(null) }
```

**Neues File:** `src/main/java/de/caluga/morphium/archive/FederatedResult.java`
- Wraps `List<T>` + `Map<Object, DocumentProvenance>` (id → Herkunft)
- `getProvenance(id)`, `containsArchivedDocuments()`

Entities mit `@AdditionalData` bekommen die Provenance automatisch (unbekanntes Feld → additionalData Map). Für alle anderen: `FederatedResult` als Rich-Result-Wrapper.

---

## 7. Cache-Integration

**Modify:** `MorphiumCacheImpl.getCacheKey(Query)` — DataSource als Discriminator anhängen:
```java
if (q.getDataSource() == DataSource.FEDERATED) base += "|ds=FEDERATED";
```

Gleicher Query auf Cluster und Federation = verschiedene Cache-Einträge.

---

## 8. Neue Dateien (Übersicht)

| Datei | Zweck |
|-------|-------|
| `config/OnlineArchiveSettings.java` | Konfiguration |
| `annotations/OnlineArchive.java` | Entity-Annotation |
| `annotations/DataSource.java` | Routing-Enum |
| `archive/ReadOnlyDriverWrapper.java` | Write-Safety für Federation |
| `archive/ArchiveCostGuard.java` | Partition-Filter-Prüfung |
| `archive/ArchiveCostGuardException.java` | Exception |
| `archive/DocumentProvenance.java` | Herkunfts-Enum |
| `archive/FederatedResult.java` | Rich Result Wrapper |
| `test/.../archive/ArchiveCostGuardTest.java` | Tests |
| `test/.../archive/QueryRoutingTest.java` | Tests |
| `test/.../archive/OnlineArchiveAnnotationTest.java` | Tests |

## 9. Existierende Dateien (Modifikationen)

| Datei | Änderung |
|-------|----------|
| `MorphiumConfig.java` | + OnlineArchiveSettings Feld + Accessor |
| `Morphium.java` | + federatedDriver Lifecycle, getFederatedDriver(), createFederatedQueryFor() |
| `Query.java` | + dataSourceOverride, setDataSource(), resolveDriverForRead(), getFindCmd() anpassen |
| `BackendType.java` | + ATLAS_DATA_FEDERATION |
| `Aggregator.java` / `AggregatorImpl.java` | + DataSource-Support |
| `MorphiumCacheImpl.java` | Cache-Key mit DataSource-Discriminator |

---

## 10. Phasen

| Phase | Scope | Aufwand |
|-------|-------|---------|
| **1** | Config + Dual-Driver + ReadOnlyWrapper | 2-3 Tage |
| **2** | @OnlineArchive + Query Routing | 3-4 Tage |
| **3** | Cost Guard | 1-2 Tage |
| **4** | Provenance + Aggregation-Routing | 2-3 Tage |
| **5** | Cache-Integration + Doku + Quarkus-Config | 2 Tage |

**Gesamtaufwand:** ~12-15 Tage

---

## 11. Testbarkeit

Beide Driver werden im Test als **InMemoryDriver** instanziiert. Der "Federation"-InMemoryDriver wird mit archivierten Dokumenten vorbestückt. So können Routing, Cost Guard und Provenance ohne echtes Atlas-Deployment getestet werden.

---

## 12. Risiken

- **Latenz:** Federation-Reads sind deutlich langsamer → separates Timeout (30s Default)
- **Query-Kompatibilität:** Federation unterstützt nicht alle MongoDB-Features (`$text`, Tailable Cursors) → Dokumentation + ggf. Fehler im ReadOnlyDriverWrapper
- **Cache-Konsistenz:** Ohne DataSource-Discriminator im Cache-Key → stale Data → Phase 5 behebt das
- **Zwei Connection-Pools:** Mehr Ressourcenverbrauch → Federation-Pool kleiner dimensioniert (50 vs 250)
- **Kosten:** Jeder Federation-Query kostet Geld (min. 10MB × $5/TB) → Cost Guard als Schutz

---

## 13. Hintergrund: Atlas Online Archive im Detail

### Archival Rules
- **DATE-basiert:** Dokumente älter als N Tage (basierend auf einem Datumsfeld) werden archiviert
- **CUSTOM-basiert:** MongoDB-Filter-Query bestimmt welche Dokumente archiviert werden
- **Schedule:** DAILY, WEEKLY, MONTHLY oder DEFAULT

### Partition Fields
- Bis zu 2-3 Felder die als grobgranularer Index im Archive fungieren
- Queries die auf Partition-Feldern filtern überspringen irrelevante Daten-Partitionen
- Queries OHNE Partition-Filter scannen das gesamte Archiv → teuer

### Was Federation NICHT kann
- Keine Writes (insert/update/delete) auf archivierte Daten
- Keine Transactions
- Keine Change Streams / Tailable Cursors
- Keine Index-Erstellung auf archivierten Daten
- Keine Dokumente > 16MB
- `$merge`/`$out` können nur auf Atlas-Cluster oder Cloud Storage schreiben, nicht zurück ins Archiv

### Kostenmodell
- Speicher: ~$0.048/GB/Monat (vs. ~$0.25+/GB für Atlas-Cluster)
- Queries: $5/TB verarbeitet, 10MB Minimum pro Query
- Konfigurierbare Limits: `bytesProcessed.query/daily/weekly/monthly` mit `BLOCK` oder `BLOCK_AND_KILL` Policy
