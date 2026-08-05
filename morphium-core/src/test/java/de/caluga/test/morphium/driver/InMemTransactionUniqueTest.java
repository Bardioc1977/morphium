package de.caluga.test.morphium.driver;

import de.caluga.morphium.IndexDescription;
import de.caluga.morphium.driver.Doc;
import de.caluga.morphium.driver.MorphiumDriverException;
import de.caluga.morphium.driver.commands.CreateIndexesCommand;
import de.caluga.morphium.driver.commands.InsertMongoCommand;
import de.caluga.morphium.driver.inmem.InMemoryDriver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("inmemory")
public class InMemTransactionUniqueTest {

    String db = "txuniqdb";
    String coll = "campaign";

    @Test
    public void insertDuplicateInSeparateTransactionsThrows() throws Exception {
        var drv = new InMemoryDriver();
        drv.connect();

        new CreateIndexesCommand(drv).setDb(db).setColl(coll)
            .addIndex(new IndexDescription().setKey(Doc.of("campaignNumber", 1)).setUnique(true))
            .execute();

        // Transaction 1: insert campaign1
        drv.startTransaction(false);
        var r1 = new InsertMongoCommand(drv).setDb(db).setColl(coll)
            .setDocuments(List.of(Doc.of("campaignNumber", "SB01")))
            .execute();
        assertFalse(r1.containsKey("writeErrors"), "first insert should succeed: " + r1);
        drv.commitTransaction();

        // Verify campaign1 is in database
        var afterCommit = drv.find(db, coll, null, null, null, 0, 0);
        assertEquals(1, afterCommit.size(), "one doc after commit: " + afterCommit);

        // Transaction 2: insert duplicate campaign1 -> should produce writeErrors
        drv.startTransaction(false);
        var collInTx = drv.find(db, coll, null, null, null, 0, 0);
        System.out.println("Collection visible in tx2: " + collInTx);
        assertEquals(1, collInTx.size(), "tx2 should see campaign1: " + collInTx);

        var r2 = new InsertMongoCommand(drv).setDb(db).setColl(coll)
            .setDocuments(List.of(Doc.of("campaignNumber", "SB01")))
            .execute();
        System.out.println("Insert 2 result: " + r2);
        assertTrue(r2.containsKey("writeErrors"), "duplicate insert should produce writeErrors: " + r2);
        drv.commitTransaction();
    }
}
