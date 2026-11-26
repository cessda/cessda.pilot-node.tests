package eu.cessda.pilotnode.capabilities;

/**
 * A node as the Node Registry lists it.
 */
public record Node(
        String name,
        String id,
        String pid,
        String endpoint,
        String logo,
        String legalEntityName,
        String legalEntityRor
) {
}
