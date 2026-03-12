package de.caluga.test.poppydb.election;

import de.caluga.poppydb.PoppyDB;
import de.caluga.poppydb.election.ElectionConfig;
import de.caluga.poppydb.election.ElectionManager;
import de.caluga.poppydb.election.ElectionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for multi-node election.
 * Starts multiple PoppyDB instances and tests leader election.
 * Uses polling instead of Thread.sleep for CI stability.
 */
public class MultiNodeElectionTest {

    private static final Logger log = LoggerFactory.getLogger(MultiNodeElectionTest.class);
    private static final long ELECTION_TIMEOUT_MS = 15000;

    private List<PoppyDB> servers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        log.info("Cleaning up {} servers", servers.size());
        for (PoppyDB server : servers) {
            try {
                server.shutdown();
            } catch (Exception e) {
                log.debug("Error shutting down server: {}", e.getMessage());
            }
        }
        servers.clear();
    }

    /**
     * Polls servers until the expected number of leaders and followers is reached,
     * or the timeout expires. Returns the leader address if found.
     */
    private String waitForElectionState(List<PoppyDB> serverList,
                                        int expectedLeaders, int expectedFollowers,
                                        long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int leaders = 0, followers = 0;
            String leaderAddr = null;
            for (PoppyDB server : serverList) {
                ElectionManager em = server.getElectionManager();
                if (em != null) {
                    if (em.getState() == ElectionState.LEADER) {
                        leaders++;
                        leaderAddr = server.getHost() + ":" + server.getPort();
                    } else if (em.getState() == ElectionState.FOLLOWER) {
                        followers++;
                    }
                }
            }
            if (leaders == expectedLeaders && followers == expectedFollowers) {
                return leaderAddr;
            }
            Thread.sleep(100);
        }
        // Final snapshot for assertion message
        int leaders = 0, followers = 0, candidates = 0;
        for (PoppyDB s : serverList) {
            ElectionManager em = s.getElectionManager();
            if (em != null) {
                switch (em.getState()) {
                    case LEADER -> leaders++;
                    case FOLLOWER -> followers++;
                    case CANDIDATE -> candidates++;
                }
            }
        }
        fail(String.format("Election did not converge within %dms: %d leaders, %d followers, %d candidates (expected %d leaders, %d followers)",
                timeoutMs, leaders, followers, candidates, expectedLeaders, expectedFollowers));
        return null; // unreachable
    }

    /**
     * Polls servers until at least one leader is found and returns it.
     */
    private PoppyDB waitForLeader(List<PoppyDB> serverList, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (PoppyDB server : serverList) {
                ElectionManager em = server.getElectionManager();
                if (em != null && em.getState() == ElectionState.LEADER) {
                    return server;
                }
            }
            Thread.sleep(100);
        }
        fail("No leader found within " + timeoutMs + "ms");
        return null; // unreachable
    }

    /**
     * Polls servers and asserts that no leader exists for the given duration.
     */
    private void assertNoLeaderFor(List<PoppyDB> serverList, long durationMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < deadline) {
            for (PoppyDB server : serverList) {
                ElectionManager em = server.getElectionManager();
                if (em != null && em.getState() == ElectionState.LEADER) {
                    fail("Unexpected leader found: " + server.getHost() + ":" + server.getPort());
                }
            }
            Thread.sleep(100);
        }
    }

    @Test
    void testSingleNodeElection() throws Exception {
        log.info("Testing single node election");

        List<String> hosts = List.of("localhost:27100");

        ElectionConfig config = new ElectionConfig()
                .setElectionTimeoutMinMs(100)
                .setElectionTimeoutMaxMs(200);

        PoppyDB server = new PoppyDB(27100, "localhost", 100, 60);
        server.configureReplicaSet("rs0", hosts, null, true, config);
        servers.add(server);

        server.start();

        waitForElectionState(servers, 1, 0, ELECTION_TIMEOUT_MS);

        assertTrue(server.isPrimary(), "Single node should be primary");
        assertEquals("localhost:27100", server.getPrimaryHost());

        ElectionManager em = server.getElectionManager();
        assertNotNull(em);
        assertEquals(ElectionState.LEADER, em.getState());
    }

    @Test
    void testThreeNodeElection() throws Exception {
        log.info("Testing three node election");

        List<String> hosts = List.of("localhost:27100", "localhost:27101", "localhost:27102");

        ElectionConfig config = new ElectionConfig()
                .setElectionTimeoutMinMs(150)
                .setElectionTimeoutMaxMs(300)
                .setHeartbeatIntervalMs(50);

        for (int i = 0; i < 3; i++) {
            int port = 27100 + i;
            PoppyDB server = new PoppyDB(port, "localhost", 100, 60);
            server.configureReplicaSet("rs0", hosts, null, true, config);
            servers.add(server);
        }

        for (PoppyDB server : servers) {
            server.start();
        }

        String leaderAddress = waitForElectionState(servers, 1, 2, ELECTION_TIMEOUT_MS);
        assertNotNull(leaderAddress);

        // Verify all servers agree on who the leader is
        for (PoppyDB server : servers) {
            ElectionManager em = server.getElectionManager();
            if (em != null && em.getState() == ElectionState.FOLLOWER) {
                assertEquals(leaderAddress, em.getCurrentLeader(),
                        "All followers should agree on leader");
            }
        }

        // Verify primary flags are consistent with election state
        for (PoppyDB server : servers) {
            ElectionManager em = server.getElectionManager();
            if (em != null) {
                boolean isLeader = em.getState() == ElectionState.LEADER;
                assertEquals(isLeader, server.isPrimary(),
                        "Primary flag should match election state");
            }
        }
    }

    @Test
    void testLeaderFailover() throws Exception {
        log.info("Testing leader failover");

        List<String> hosts = List.of("localhost:27100", "localhost:27101", "localhost:27102");

        ElectionConfig config = new ElectionConfig()
                .setElectionTimeoutMinMs(150)
                .setElectionTimeoutMaxMs(300)
                .setHeartbeatIntervalMs(50);

        for (int i = 0; i < 3; i++) {
            int port = 27100 + i;
            PoppyDB server = new PoppyDB(port, "localhost", 100, 60);
            server.configureReplicaSet("rs0", hosts, null, true, config);
            servers.add(server);
        }

        for (PoppyDB server : servers) {
            server.start();
        }

        // Wait for initial election
        PoppyDB leader = waitForLeader(servers, ELECTION_TIMEOUT_MS);
        String originalLeaderAddress = leader.getHost() + ":" + leader.getPort();
        log.info("Original leader: {}", originalLeaderAddress);

        // Stop the leader
        log.info("Stopping leader...");
        leader.shutdown();
        servers.remove(leader);

        // Wait for new election among remaining servers
        String newLeaderAddress = waitForElectionState(servers, 1, 1, ELECTION_TIMEOUT_MS);
        assertNotEquals(originalLeaderAddress, newLeaderAddress, "New leader should be different");
    }

    @Test
    void testNoElectionWithoutQuorum() throws Exception {
        log.info("Testing no election without quorum");

        List<String> hosts = List.of("localhost:27100", "localhost:27101", "localhost:27102");

        ElectionConfig config = new ElectionConfig()
                .setElectionTimeoutMinMs(100)
                .setElectionTimeoutMaxMs(200)
                .setHeartbeatIntervalMs(50);

        // Start only one server (can't get majority of 3)
        PoppyDB server = new PoppyDB(27100, "localhost", 100, 60);
        server.configureReplicaSet("rs0", hosts, null, true, config);
        servers.add(server);

        server.start();

        // Verify no leader emerges over several election cycles
        assertNoLeaderFor(servers, 2000);

        assertFalse(server.isPrimary(), "Single node in 3-node cluster should not become primary");
    }

    @Test
    void testGracefulStepDown() throws Exception {
        log.info("Testing graceful stepdown");

        List<String> hosts = List.of("localhost:27100", "localhost:27101", "localhost:27102");

        ElectionConfig config = new ElectionConfig()
                .setElectionTimeoutMinMs(150)
                .setElectionTimeoutMaxMs(300)
                .setHeartbeatIntervalMs(50);

        for (int i = 0; i < 3; i++) {
            int port = 27100 + i;
            PoppyDB server = new PoppyDB(port, "localhost", 100, 60);
            server.configureReplicaSet("rs0", hosts, null, true, config);
            servers.add(server);
        }

        for (PoppyDB server : servers) {
            server.start();
        }

        // Wait for initial election
        PoppyDB leader = waitForLeader(servers, ELECTION_TIMEOUT_MS);
        String originalLeaderAddress = leader.getHost() + ":" + leader.getPort();
        log.info("Original leader: {}", originalLeaderAddress);

        // Request stepdown with short period so new election can happen
        ElectionManager leaderEm = leader.getElectionManager();
        log.info("Requesting stepdown...");
        boolean stepdownResult = leaderEm.stepDown(2, 0, true);  // 2 second no-election period
        assertTrue(stepdownResult, "Stepdown should succeed");

        // Wait for new election — original leader is blocked, so a different node must win
        long deadline = System.currentTimeMillis() + ELECTION_TIMEOUT_MS;
        String newLeaderAddress = null;
        while (System.currentTimeMillis() < deadline) {
            for (PoppyDB server : servers) {
                ElectionManager em = server.getElectionManager();
                if (em != null && em.getState() == ElectionState.LEADER
                        && !(server.getHost() + ":" + server.getPort()).equals(originalLeaderAddress)) {
                    newLeaderAddress = server.getHost() + ":" + server.getPort();
                    break;
                }
            }
            if (newLeaderAddress != null) break;
            Thread.sleep(100);
        }

        assertNotEquals(ElectionState.LEADER, leaderEm.getState(),
                "Original leader should have stepped down");
        assertNotNull(newLeaderAddress, "Should have a new leader after stepdown");
        assertNotEquals(originalLeaderAddress, newLeaderAddress,
                "New leader should be different from original (which is blocked)");

        log.info("Stepdown test passed: {} -> {}", originalLeaderAddress, newLeaderAddress);
    }

    @Test
    void testFreezePreventElection() throws Exception {
        log.info("Testing freeze prevents election");

        List<String> hosts = List.of("localhost:27100", "localhost:27101", "localhost:27102");

        ElectionConfig config = new ElectionConfig()
                .setElectionTimeoutMinMs(100)
                .setElectionTimeoutMaxMs(200)
                .setHeartbeatIntervalMs(50);

        for (int i = 0; i < 3; i++) {
            int port = 27100 + i;
            PoppyDB server = new PoppyDB(port, "localhost", 100, 60);
            server.configureReplicaSet("rs0", hosts, null, true, config);
            servers.add(server);
        }

        for (PoppyDB server : servers) {
            server.start();
        }

        // Wait for initial election
        PoppyDB leader = waitForLeader(servers, ELECTION_TIMEOUT_MS);
        assertNotNull(leader, "Should have a leader");

        // Freeze non-leader nodes
        for (PoppyDB server : servers) {
            ElectionManager em = server.getElectionManager();
            if (em != null && em.getState() != ElectionState.LEADER) {
                em.freeze(10);
                log.info("Froze node {}:{}", server.getHost(), server.getPort());
            }
        }

        // Stop the leader
        String originalLeaderAddress = leader.getHost() + ":" + leader.getPort();
        log.info("Stopping leader: {}", originalLeaderAddress);
        leader.shutdown();
        servers.remove(leader);

        // No new leader should be elected because remaining nodes are frozen
        assertNoLeaderFor(servers, 1500);

        // Unfreeze nodes
        for (PoppyDB server : servers) {
            ElectionManager em = server.getElectionManager();
            if (em != null) {
                em.unfreeze();
                log.info("Unfroze node {}:{}", server.getHost(), server.getPort());
            }
        }

        // Now we should get a new leader
        waitForElectionState(servers, 1, 1, ELECTION_TIMEOUT_MS);
    }
}
