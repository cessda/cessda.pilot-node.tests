package eu.cessda.pilotnode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.cessda.pilotnode.capabilities.CheckNodeCapabilities;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The messages a job reports are read in a toast, so they stay short whatever went wrong. */
class JobMessageLengthTest {

    private static List<CheckNodeCapabilities.NodeProblem> problems(int count, String reason) {
        List<CheckNodeCapabilities.NodeProblem> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(new CheckNodeCapabilities.NodeProblem("Node" + i, reason));
        }
        return list;
    }

    @Test
    void severalProblemNodesAreNamedWithoutTheirReasons() {
        String message = new CheckNodeCapabilities.Result(12, problems(8, "HTTP 502 from a very long explanation")).message();
        assertTrue(message.contains("8 with problems: Node1, Node2, Node3, Node4, Node5 and 3 more"), message);
        assertFalse(message.contains("502"), message);
    }

    @Test
    void oneProblemNodeIsNamedWithItsReasonShortened() {
        String longReason = "x".repeat(500);
        String message = new CheckNodeCapabilities.Result(12, problems(1, longReason)).message();
        assertTrue(message.contains("Node1 ("), message);
        assertTrue(message.length() < 200, message.length() + ": " + message);
        assertTrue(message.endsWith("…)"), message);
    }

    @Test
    void aShortReasonIsGivenInFull() {
        assertTrue(new CheckNodeCapabilities.Result(1, problems(1, "HTTP 502")).message().endsWith("Node1 (HTTP 502)"));
    }

    @Test
    void checkAllNamesProblemNodesAndLeavesOutWhy() {
        CheckAll.Result result = new CheckAll.Result(12);
        result.nodeProblems = problems(5, "a reason that would make the message very long indeed");
        result.capabilitiesError = "Connection reset by peer while reading the Federation Registry response body";
        String summary = result.summary();
        assertTrue(summary.contains("Node Capabilities had problems with 5 node(s): Node1, Node2, Node3 and 2 more."), summary);
        assertTrue(summary.contains("Node Capabilities failed — used existing data."), summary);
        assertFalse(summary.contains("a reason"), summary);
        assertFalse(summary.contains("Connection reset"), summary);
        assertEquals(-1, summary.indexOf("Node4"), summary);
    }
}
