package eu.cessda.pilotnode.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import eu.cessda.pilotnode.common.CheckMode;

/** Where a node's reports are looked for, including the place earlier versions kept the lenient ones. */
class ReportFilesTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String FILE = "endpoint_report.json";

    @TempDir
    Path data;

    private Path write(String relative, String json) throws IOException {
        Path p = data.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, json);
        return p;
    }

    private java.util.Optional<Path> find(CheckMode mode) {
        return ReportFiles.find(data, mode, "N", FILE, M);
    }

    @Test
    void aStrictReportIsInTheNodesFolderAndSaysSo() throws IOException {
        Path strict = write("N/" + FILE, "{\"mode\":\"strict\"}");
        assertEquals(strict, find(CheckMode.STRICT).orElseThrow());
    }

    @Test
    void aReportInTheNodesFolderThatIsNotStrictIsAnEarlierVersionsLenientOneAndNotStrict() throws IOException {
        write("N/" + FILE, "{\"capabilities\":[]}");
        assertTrue(find(CheckMode.STRICT).isEmpty());
        write("N/" + FILE, "{\"mode\":\"lenient\"}");
        assertTrue(find(CheckMode.STRICT).isEmpty());
    }

    @Test
    void aLenientReportIsInTheLenientFolder() throws IOException {
        Path lenient = write("N/lenient/" + FILE, "{\"mode\":\"lenient\"}");
        assertEquals(lenient, find(CheckMode.LENIENT).orElseThrow());
    }

    @Test
    void untilALenientRunHasWrittenThereAnEarlierVersionsFileIsUsed() throws IOException {
        Path legacy = write("N/" + FILE, "{\"capabilities\":[]}");
        assertEquals(legacy, find(CheckMode.LENIENT).orElseThrow());
    }

    @Test
    void theLenientFolderWinsOverTheEarlierVersionsFile() throws IOException {
        write("N/" + FILE, "{\"capabilities\":[]}");
        Path lenient = write("N/lenient/" + FILE, "{\"mode\":\"lenient\"}");
        assertEquals(lenient, find(CheckMode.LENIENT).orElseThrow());
    }

    @Test
    void aStrictReportIsNeverTakenForALenientOne() throws IOException {
        write("N/" + FILE, "{\"mode\":\"strict\"}");
        assertTrue(find(CheckMode.LENIENT).isEmpty());
    }

    @Test
    void nothingIsFoundWhereNothingIs() {
        assertTrue(find(CheckMode.STRICT).isEmpty());
        assertTrue(find(CheckMode.LENIENT).isEmpty());
        assertTrue(ReportFiles.read(data, CheckMode.LENIENT, "N", FILE, M).isEmpty());
    }

    @Test
    void aReportThatCannotBeParsedIsNotStrictAndReadsAsNothing() throws IOException {
        write("N/" + FILE, "{ not json");
        assertTrue(find(CheckMode.STRICT).isEmpty());
        assertFalse(ReportFiles.read(data, CheckMode.LENIENT, "N", FILE, M).isPresent());
    }
}
