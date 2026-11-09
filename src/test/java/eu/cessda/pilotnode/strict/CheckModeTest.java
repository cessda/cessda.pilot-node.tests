package eu.cessda.pilotnode.strict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class CheckModeTest {

    @Test
    void blankOrMissingMeansLenient() {
        assertEquals(CheckMode.LENIENT, CheckMode.parse(null));
        assertEquals(CheckMode.LENIENT, CheckMode.parse(""));
        assertEquals(CheckMode.LENIENT, CheckMode.parse("   "));
    }

    @Test
    void namesAreMatchedIgnoringCaseAndSpaces() {
        assertEquals(CheckMode.STRICT, CheckMode.parse("strict"));
        assertEquals(CheckMode.STRICT, CheckMode.parse(" STRICT "));
        assertEquals(CheckMode.LENIENT, CheckMode.parse("Lenient"));
    }

    @Test
    void anUnknownModeIsRejectedWithTheValidOnes() {
        var e = assertThrows(IllegalArgumentException.class, () -> CheckMode.parse("harsh"));
        assertTrue(e.getMessage().contains("harsh") && e.getMessage().contains("lenient") && e.getMessage().contains("strict"));
    }

    @Test
    void eachModeKeepsItsResultsApart() {
        Path data = Path.of("dashboard/data");
        assertEquals(data, CheckMode.LENIENT.resolve(data));
        assertEquals(data.resolve("strict"), CheckMode.STRICT.resolve(data));
    }
}
