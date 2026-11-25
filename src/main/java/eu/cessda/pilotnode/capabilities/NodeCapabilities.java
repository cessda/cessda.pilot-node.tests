package eu.cessda.pilotnode.capabilities;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

public interface NodeCapabilities {
    ObjectNode check(Node node) throws IOException, InterruptedException;
}
