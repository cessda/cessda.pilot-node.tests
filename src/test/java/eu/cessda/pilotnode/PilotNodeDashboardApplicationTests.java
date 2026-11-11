package eu.cessda.pilotnode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void dataApiBlocksPathTraversal() throws Exception {
        mockMvc.perform(get("/api/data/../../etc/passwd")).andExpect(status().isNotFound());
    }
}