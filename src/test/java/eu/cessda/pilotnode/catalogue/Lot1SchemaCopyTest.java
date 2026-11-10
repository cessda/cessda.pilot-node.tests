package eu.cessda.pilotnode.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The Lot-1 schemas are copied from the Beyond-to-Lot-1 adapter project, which has them too. When that project is
 * checked out next to this one, the copies must be identical, so that the two cannot drift apart unnoticed.
 */
class Lot1SchemaCopyTest {

    @Test
    void theBundledSchemasMatchTheAdapterProjectsCopies() throws IOException {
        Path ours = Path.of("src/main/resources/schemas");
        Path theirs = Path.of("..", "eosc.service-cat-api.adapter", "src/main/resources/schemas");
        assumeTrue(Files.isDirectory(theirs), "the adapter project is not checked out next to this one");
        for (String file : new String[]{"eosc-lot1-v1.0.0.json", "eosc-lot1-v2.0.0.json"}) {
            assertEquals(FakeNode.MAPPER.readTree(Files.readString(theirs.resolve(file))),
                    FakeNode.MAPPER.readTree(Files.readString(ours.resolve(file))), file + " differs");
        }
    }
}
