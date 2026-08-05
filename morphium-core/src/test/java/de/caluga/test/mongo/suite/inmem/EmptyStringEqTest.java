package de.caluga.test.mongo.suite.inmem;

import de.caluga.morphium.aggregation.Aggregator;
import de.caluga.morphium.query.Query;
import de.caluga.test.mongo.suite.data.UncachedObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnose-Test fuer den Bug-Report: ".f(field).eq(\"\") (empty string) silently drops the filter term".
 * Prueft alle drei Ebenen: toQueryObject(), find() und aggregation $match.
 */
public class EmptyStringEqTest extends MorphiumInMemTestBase {

    @Test
    public void emptyStringEqMustProduceFilterTerm() {
        Query<UncachedObject> q = morphium.createQueryFor(UncachedObject.class)
                .f("counter").eq(5)
                .f("str_value").eq("");   // empty string

        Map<String, Object> qo = q.toQueryObject();
        log.info("toQueryObject(): " + qo);

        // Beide Bedingungen muessen die Serialisierung ueberleben.
        // Erwartet: { $and: [ {counter:5}, {str_value:""} ] }
        assertTrue(qo.containsKey("$and"), "Erwartet $and-Wrapper bei zwei Termen, war: " + qo);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> and = (List<Map<String, Object>>) qo.get("$and");
        assertEquals(2, and.size(), "Beide Terme muessen erhalten bleiben, war: " + qo);

        boolean hasEmptyStrValue = and.stream()
                .anyMatch(m -> m.containsKey("str_value") && "".equals(m.get("str_value")));
        assertTrue(hasEmptyStrValue, "str_value:\"\" Term fehlt! toQueryObject = " + qo);
    }

    @Test
    public void emptyStringEqMustMatchOnlyEmptyDocs_inMemory() {
        // store: {counter:1, str_value:""} und {counter:2, str_value:"x"}
        morphium.store(new UncachedObject("", 1));
        morphium.store(new UncachedObject("x", 2));
        waitForWrites();

        long total = morphium.createQueryFor(UncachedObject.class).countAll();
        assertEquals(2, total, "Setup: zwei Dokumente erwartet");

        List<UncachedObject> res = morphium.createQueryFor(UncachedObject.class)
                .f("str_value").eq("")
                .asList();
        log.info("eq(\"\") lieferte " + res.size() + " von " + total + " Dokumenten");

        assertEquals(1, res.size(), "eq(\"\") muss GENAU das leere Dokument liefern, nicht alle!");
        assertEquals(1, res.get(0).getCounter());
    }

    @Test
    public void emptyStringEqInAggregationMatch_inMemory() {
        morphium.store(new UncachedObject("", 1));
        morphium.store(new UncachedObject("", 2));
        morphium.store(new UncachedObject("x", 3));
        waitForWrites();

        Query<UncachedObject> q = morphium.createQueryFor(UncachedObject.class)
                .f("str_value").eq("");

        Aggregator<UncachedObject, Map> agg = morphium.createAggregator(UncachedObject.class, Map.class);
        agg.match(q);
        agg.group("$str_value").sum("count", 1).end();

        List<Map> result = agg.aggregate();
        log.info("Aggregation $match eq(\"\") Ergebnis: " + result);

        long totalCount = result.stream()
                .mapToLong(m -> ((Number) m.get("count")).longValue())
                .sum();
        assertEquals(2, totalCount, "$match muss nur die zwei leeren Dokumente zaehlen, nicht alle drei!");
    }

    /**
     * EXAKTES Live-Szenario aus dem Bug-Report: zwei Terme campaignNumber + testGroup="".
     * Der Profiler zeigte nur {campaignNumber:...} OHNE $and -> beweist, dass live nur EIN
     * Term ankam. Dieser Test prueft, ob morphium bei zwei Termen (zweiter = leerer String)
     * den $match korrekt mit beiden Termen aufbaut.
     */
    @Test
    public void twoTermsWithEmptyStringInAggregationMatch_inMemory() {
        // counter steht fuer campaignNumber, str_value fuer testGroup
        morphium.store(new UncachedObject("", 1));   // campaign 1, testGroup ""  -> soll zaehlen
        morphium.store(new UncachedObject("", 1));   // campaign 1, testGroup ""  -> soll zaehlen
        morphium.store(new UncachedObject("x", 1));  // campaign 1, testGroup "x" -> NICHT
        morphium.store(new UncachedObject("", 2));   // andere campaign            -> NICHT
        waitForWrites();

        Query<UncachedObject> q = morphium.createQueryFor(UncachedObject.class)
                .f("counter").eq(1)
                .f("str_value").eq("");   // leerer String als zweiter Term

        log.info("Zwei-Term toQueryObject(): " + q.toQueryObject());

        Aggregator<UncachedObject, Map> agg = morphium.createAggregator(UncachedObject.class, Map.class);
        agg.match(q);
        agg.group("$counter").sum("count", 1).end();

        List<Map> result = agg.aggregate();
        log.info("Zwei-Term $match Ergebnis: " + result);

        long totalCount = result.stream()
                .mapToLong(m -> ((Number) m.get("count")).longValue())
                .sum();
        assertEquals(2, totalCount,
                "$match mit campaignNumber + testGroup=\"\" muss GENAU 2 liefern, nicht alle 3 der Kampagne!");
    }
}
