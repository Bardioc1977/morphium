package de.caluga.test.mongo.suite.base;

import de.caluga.morphium.Morphium;
import de.caluga.morphium.MorphiumConfig;
import de.caluga.morphium.annotations.Entity;
import de.caluga.morphium.annotations.Id;
import de.caluga.morphium.driver.wire.PooledDriver;
import de.caluga.morphium.query.Query;
import de.caluga.test.support.TestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LIVE-Test gegen die laufende datona-ota-auth MongoDB (localhost:27017).
 * Bildet exakt die CarbViolationEntity-Konstellation nach und fuehrt die identische
 * Aggregation aus wie die App: .f("campaignNumber").eq(...).f("testGroup").eq("")
 *
 * DB-Wahrheit (per mongosh verifiziert):
 *   campaignNumber=SIM-318D963F total=40, testGroup=""=4
 *
 * Nur aktiv, wenn MORPHIUM_LIVE_TEST=1 gesetzt ist (kein CI-Lauf).
 */
@EnabledIfEnvironmentVariable(named = "MORPHIUM_LIVE_TEST", matches = "1")
public class EmptyStringEqLiveTest {

    private static final Logger log = LoggerFactory.getLogger(EmptyStringEqLiveTest.class);
    private static final String CAMPAIGN = "SIM-318D963F";

    private Morphium morphium;

    /** 1:1-Abbild der CarbViolationEntity (Felder untranslated, collectionName carbViolation). */
    @Entity(collectionName = "carbViolation", translateCamelCase = false)
    public static class CarbViolationEntity {
        @Id
        private String id;
        private String campaignNumber;
        private String testGroup;
        private String violationType;

        public String getId() { return id; }
        public String getCampaignNumber() { return campaignNumber; }
        public String getTestGroup() { return testGroup; }
        public String getViolationType() { return violationType; }
    }

    @BeforeEach
    public void setup() {
        // Verbindung via MONGODB_URI/MORPHIUM_URI bzw. Default localhost:27017.
        // Standard-DB hier auf datona-ota-auth setzen, falls keine URI eine DB vorgibt.
        MorphiumConfig cfg = TestConfig.forDriver(PooledDriver.driverName);
        if (cfg.connectionSettings().getDatabase() == null
                || cfg.connectionSettings().getDatabase().isBlank()
                || "test".equals(cfg.connectionSettings().getDatabase())) {
            cfg.connectionSettings().setDatabase("datona-ota-auth");
        }
        cfg.clusterSettings().setReplicaset(false);
        morphium = new Morphium(cfg);
        log.info("Connected to DB: {}", morphium.getConfig().connectionSettings().getDatabase());
    }

    @AfterEach
    public void tearDown() {
        if (morphium != null) morphium.close();
    }

    @Test
    public void liveEmptyTestGroupMustNarrowToFour() {
        long total = morphium.createQueryFor(CarbViolationEntity.class)
                .f("campaignNumber").eq(CAMPAIGN).countAll();
        log.info("Live total fuer campaign {} = {}", CAMPAIGN, total);
        assertEquals(40, total, "Setup-Annahme: 40 Dokumente in der Kampagne");

        // ---- exakt die App-Query ----
        Query<CarbViolationEntity> q = morphium.createQueryFor(CarbViolationEntity.class)
                .f("campaignNumber").eq(CAMPAIGN)
                .f("testGroup").eq("");   // leerer String

        log.info("Live toQueryObject(): {}", q.toQueryObject());

        // 1) find()
        long found = q.countAll();
        log.info("find().countAll() mit testGroup=\"\" = {}", found);
        assertEquals(4, found, "find() mit testGroup=\"\" muss GENAU 4 liefern, nicht 40!");

        // 2) Aggregation $match (der Pfad aus dem Bug-Report)
        var agg = morphium.createAggregator(CarbViolationEntity.class, Map.class);
        agg.match(q);
        agg.group("$violationType").sum("count", 1).end();
        List<Map> result = agg.aggregate();
        log.info("Aggregation $match Ergebnis: {}", result);

        long aggCount = result.stream()
                .mapToLong(m -> ((Number) m.get("count")).longValue())
                .sum();
        assertEquals(4, aggCount, "Aggregation $match mit testGroup=\"\" muss GENAU 4 zaehlen, nicht 40!");
    }

    @Test
    public void liveNonEmptyAndAbsentStillCorrect() {
        // Gegentest: existierender Wert
        long ax = morphium.createQueryFor(CarbViolationEntity.class)
                .f("campaignNumber").eq(CAMPAIGN)
                .f("testGroup").eq("AX85PE5BNVK0").countAll();
        log.info("testGroup=AX85PE5BNVK0 -> {}", ax);
        assertEquals(12, ax, "Bug-Report-Matrix: AX85PE5BNVK0 => 12");

        // Gegentest: nicht existierender Wert
        long zz = morphium.createQueryFor(CarbViolationEntity.class)
                .f("campaignNumber").eq(CAMPAIGN)
                .f("testGroup").eq("ZZZZZZZZ").countAll();
        log.info("testGroup=ZZZZZZZZ -> {}", zz);
        assertEquals(0, zz, "Bug-Report-Matrix: nicht existent => 0");
    }
}
