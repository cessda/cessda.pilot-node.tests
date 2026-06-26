package eu.cessda.pilotnode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PilotNodeDashboardApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void indexPageLoads() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void nodePageLoads() throws Exception {
        mockMvc.perform(get("/node.html")).andExpect(status().isOk());
    }

    @Test
    void dataApiRejectsNonJsonPaths() throws Exception {
        mockMvc.perform(get("/api/data/somefile.txt")).andExpect(status().isNotFound());
    }

    @Test
    void dataApiBlocksPathTraversal() throws Exception {
        mockMvc.perform(get("/api/data/../../etc/passwd")).andExpect(status().isNotFound());
    }
}