package eu.cessda.pilotnode.capabilities;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

public interface NodeCapabilities {

    // JSON constants
    String LEGAL_ENTITY_PATH = "legal_entity";
    String NAME_PATH = "name";
    String ID_PATH = "id";
    String PID_PATH = "pid";
    String NODE_ENDPOINT_PATH = "node_endpoint";
    String LOGO_PATH = "logo";
    String ROR_ID_PATH = "ror_id";
    String NODE_NAME_PATH = "node_name";
    String NODE_ID_PATH = "node_id";
    String NODE_PID_PATH = "node_pid";
    String ERROR_PATH = "error";

    String NODE_DETAILS_FIELD = "node_details";

    String PROTOCOL_FIELD = "protocol";
    String API_SPEC_FIELD = "api_spec";
    String VERSION_FIELD = "version";
    String STATUS_FIELD = "status";
    String ENDPOINTS_FIELD = "endpoints";
    String ENDPOINT_FIELD = "endpoint";
    String ABSENT = "absent";
    String CAPABILITIES_FIELD = "capabilities";
    String CAPABILITY_TYPE_FIELD = "capability_type";
    String DECLARED_STATUS_FIELD = "declared_status";
    String HTTP_CODE_FIELD = "http_code";
    String CAPABILITIES_STALE_FIELD = "capabilities_stale";
    String CAPABILITIES_LAST_FETCHED_FIELD = "capabilities_last_fetched";
    String TOTAL_CAPABILITIES_FIELD = "total_capabilities";
    String AVAILABLE_CAPABILITIES_FIELD = "available_capabilities";
    String REPORT_FILE_FIELD = "report_file";
    String GENERATED_FIELD = "generated";
    String REPORT_WRITE_ERROR_FIELD = "report_write_error";
    String OPENAPI_FIELD = "openapi";
    String SWAGGER_FIELD = "swagger";

    ObjectNode check(Node node) throws IOException, InterruptedException;
}
