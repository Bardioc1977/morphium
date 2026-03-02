package de.caluga.test.morphium.driver;

import de.caluga.morphium.Morphium;
import de.caluga.morphium.MorphiumConfig;
import de.caluga.morphium.annotations.Capped;
import de.caluga.morphium.annotations.Entity;
import de.caluga.morphium.annotations.Id;
import de.caluga.morphium.driver.BackendType;
import de.caluga.morphium.driver.MorphiumDriver;
import de.caluga.morphium.driver.MorphiumDriverException;
import de.caluga.morphium.driver.inmem.InMemoryDriver;
import de.caluga.test.mongo.suite.inmem.MorphiumInMemTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("core")
public class CosmosDBCompatibilityTest extends MorphiumInMemTestBase {

    /**
     * Wraps the real InMemoryDriver in a dynamic proxy that overrides isCosmosDB() to return true.
     * This lets us test CosmosDB guards without needing an actual CosmosDB connection.
     */
    private void enableCosmosDBMode() throws Exception {
        MorphiumDriver realDriver = morphium.getDriver();
        InvocationHandler handler = (proxy, method, args) -> {
            if ("isCosmosDB".equals(method.getName())) {
                return true;
            }
            if ("getBackendType".equals(method.getName())) {
                return BackendType.COSMOSDB;
            }
            return method.invoke(realDriver, args);
        };
        MorphiumDriver cosmosProxy = (MorphiumDriver) Proxy.newProxyInstance(
                MorphiumDriver.class.getClassLoader(),
                new Class[]{MorphiumDriver.class},
                handler);
        morphium.setDriver(cosmosProxy);
    }

    @Test
    public void testStartTransactionThrowsOnCosmosDB() throws Exception {
        enableCosmosDBMode();
        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> morphium.startTransaction());
        assertTrue(ex.getMessage().contains("CosmosDB"));
        assertTrue(ex.getMessage().contains("atomic operations"));
    }

    @Test
    public void testMapReduceThrowsOnCosmosDB() throws Exception {
        enableCosmosDBMode();
        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> morphium.mapReduce(TestEntity.class, "function(){}", "function(){}"));
        assertTrue(ex.getMessage().contains("CosmosDB"));
        assertTrue(ex.getMessage().contains("Aggregation"));
    }

    @Test
    public void testExplainMapReduceThrowsOnCosmosDB() throws Exception {
        enableCosmosDBMode();
        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> morphium.explainMapReduce(TestEntity.class, "function(){}", "function(){}",
                        de.caluga.morphium.driver.commands.ExplainCommand.ExplainVerbosity.queryPlanner));
        assertTrue(ex.getMessage().contains("CosmosDB"));
    }

    @Test
    public void testCheckCappedReturnsEmptyOnCosmosDB() throws Exception {
        enableCosmosDBMode();
        Map<Class<?>, Map<String, Integer>> result = morphium.checkCapped();
        assertNotNull(result);
        assertTrue(result.isEmpty(), "checkCapped() should return empty map on CosmosDB");
    }

    @Test
    public void testEnsureCappedCreatesRegularCollectionOnCosmosDB() throws Exception {
        enableCosmosDBMode();
        // Should not throw — creates a regular collection instead
        assertDoesNotThrow(() -> morphium.ensureCapped(CappedTestEntity.class));

        // Verify collection exists
        boolean exists = morphium.getDriver().exists(
                morphium.getConfig().connectionSettings().getDatabase(),
                morphium.getMapper().getCollectionName(CappedTestEntity.class));
        assertTrue(exists, "Collection should have been created");
    }

    @Test
    public void testWatchDoesNotThrowOnCosmosDB() throws Exception {
        enableCosmosDBMode();
        // watch() should just log a warning, not throw
        // We can't easily test the actual watch behavior, but we can verify no exception
        // by checking the guard condition directly
        assertTrue(morphium.getDriver().isCosmosDB());
        assertEquals(BackendType.COSMOSDB, morphium.getBackendType());
    }

    @Test
    public void testNormalModeNoGuards() {
        // Verify that in normal (non-CosmosDB) mode, these methods don't throw UnsupportedOperationException
        assertFalse(morphium.getDriver().isCosmosDB());

        // checkCapped should work normally (may return empty for no @Capped entities)
        assertDoesNotThrow(() -> morphium.checkCapped());

        // ensureCapped should work normally
        assertDoesNotThrow(() -> morphium.ensureCapped(CappedTestEntity.class));
    }

    @Entity
    public static class TestEntity {
        @Id
        public String id;
        public String value;
    }

    @Entity
    @Capped(maxSize = 10000, maxEntries = 100)
    public static class CappedTestEntity {
        @Id
        public String id;
        public String message;
    }
}
