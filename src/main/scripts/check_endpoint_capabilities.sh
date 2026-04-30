#!/bin/bash

# EOSC Beyond Node Registry Endpoint Capabilities Checker
# Queries the Node Registry and checks availability of capabilities for all
# registered nodes.
#
<<<<<<< Updated upstream:src/main/scripts/check_endpoint_capabilities.sh
# Usage: ./check_endpoint_capabilities.sh <API_KEY> [format]
#   format: text, json, or both (default: both)
#   Example: ./check_endpoint_capabilities.sh myapikey json
=======
# Usage:           ./check_node_capabilities.sh API_KEY [format] [dashboard_dir]
# format:          text, json, or both (default: both)
# dashboard_dir:   Path to the dashboard data directory (optional)
#                  Per-node output is written to <dashboard_dir>/<NODE_NAME>/
#                  The registry summary is written to <dashboard_dir>/
#                  Example: ./check_node_capabilities.sh myapikey json ../dashboard/data
>>>>>>> Stashed changes:src/main/scripts/check_node_capabilities.sh

NODE_REGISTRY_URL="https://node-devel.eosc.grnet.gr/federation-backend/tenants/eosc-beyond/nodes"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# --- Validate API key argument ---
if [ -z "$1" ]; then
    echo "Error: API_KEY is required."
    echo "Usage: $0 <API_KEY> [text|json|both]"
    exit 1
fi

API_KEY="$1"

# --- Parse optional format argument ---
FORMAT="${2:-both}"

case "$FORMAT" in
    text|txt)
        GENERATE_TEXT=true
        GENERATE_JSON=false
        ;;
    json)
        GENERATE_TEXT=false
        GENERATE_JSON=true
        ;;
    both)
        GENERATE_TEXT=true
        GENERATE_JSON=true
        ;;
    *)
        echo "Invalid format: $FORMAT"
        echo "Usage: $0 <API_KEY> [text|json|both]"
        exit 1
        ;;
esac

# Colours for terminal output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Colour

echo "=========================================="
echo "EOSC Beyond Node Registry"
echo "Endpoint Availability Report"
echo "=========================================="
echo "Generated: $(date)"
echo "Registry: $NODE_REGISTRY_URL"
echo "=========================================="
echo ""

# Fetch nodes from registry
echo "Fetching node list from registry..."
NODES_DATA=$(curl -s -H "X-Api-Key: $API_KEY" "$NODE_REGISTRY_URL")

if [ $? -ne 0 ] || [ -z "$NODES_DATA" ]; then
    echo -e "${RED}ERROR: Failed to fetch data from Node Registry${NC}"
    exit 1
fi

echo "Node registry data retrieved successfully!"
echo ""

# Check if jq is available
if ! command -v jq &> /dev/null; then
    echo -e "${YELLOW}WARNING: jq not found. Install jq for better reliability.${NC}"
    echo "Continuing with basic parsing..."
    echo ""
    USE_JQ=false
else
    USE_JQ=true
fi

# Create summary report files
SUMMARY_FILE_TXT="node_registry_summary_${TIMESTAMP}.txt"
SUMMARY_FILE_JSON="node_registry_summary_${TIMESTAMP}.json"

if [ "$GENERATE_TEXT" = true ]; then
    {
        echo "=========================================="
        echo "EOSC Beyond Node Registry"
        echo "Endpoint Availability Summary"
        echo "=========================================="
        echo "Generated: $(date)"
        echo "Registry: $NODE_REGISTRY_URL"
        echo "=========================================="
        echo ""
    } > "$SUMMARY_FILE_TXT"
fi

if [ "$GENERATE_JSON" = true ]; then
    cat > "$SUMMARY_FILE_JSON" <<EOF
{
  "generated": "$(date -Iseconds)",
  "registry_source": "$NODE_REGISTRY_URL",
  "nodes": []
}
EOF
fi

# Function to sanitize node name for filename
sanitize_name() {
    echo "$1" | sed 's/[^a-zA-Z0-9-]/_/g'
}

# Function to check a single node's capabilities
check_node_capabilities() {
    local NODE_NAME="$1"
    local NODE_ID="$2"
    local NODE_PID="$3"
    local NODE_ENDPOINT="$4"
    local NODE_LOGO="$5"
    local LEGAL_ENTITY_NAME="$6"
    local LEGAL_ENTITY_ROR="$7"
    
    local SAFE_NODE_NAME=$(sanitize_name "$NODE_NAME")
    local NODE_REPORT_TXT="endpoint_report_${SAFE_NODE_NAME}_${TIMESTAMP}.txt"
    local NODE_REPORT_JSON="endpoint_report_${SAFE_NODE_NAME}_${TIMESTAMP}.json"
    
    echo -e "${BLUE}========================================${NC}"
    echo -e "${CYAN}Node: ${NODE_NAME}${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo "ID: $NODE_ID"
    echo "PID: $NODE_PID"
    echo "Endpoint: $NODE_ENDPOINT"
    echo "Legal Entity: $LEGAL_ENTITY_NAME"
    echo ""
    
    # Initialise node text report
    if [ "$GENERATE_TEXT" = true ]; then
        {
            echo "=========================================="
            echo "Node: ${NODE_NAME}"
            echo "=========================================="
            echo "Generated: $(date)"
            echo "Node ID: $NODE_ID"
            echo "Node PID: $NODE_PID"
            echo "Node Endpoint: $NODE_ENDPOINT"
            echo "Legal Entity: $LEGAL_ENTITY_NAME"
            echo "Legal Entity ROR: $LEGAL_ENTITY_ROR"
            echo "Logo: $NODE_LOGO"
            echo "=========================================="
            echo ""
        } > "$NODE_REPORT_TXT"
    fi
    
    # Fetch capabilities for this node
    echo "Fetching capabilities for $NODE_NAME..."
    CAPABILITIES_DATA=$(curl -s "$NODE_ENDPOINT")
    
    if [ $? -ne 0 ] || [ -z "$CAPABILITIES_DATA" ]; then
        echo -e "${RED}ERROR: Failed to fetch capabilities from $NODE_ENDPOINT${NC}"
        if [ "$GENERATE_TEXT" = true ]; then
            echo "ERROR: Failed to fetch capabilities from endpoint" >> "$NODE_REPORT_TXT"
        fi
        echo ""
        return 1
    fi
    
    echo "Capabilities retrieved successfully!"
    echo ""
    
    # Initialise counters with temp files (to persist across subshell)
    COUNTER_FILE="/tmp/node_counters_${SAFE_NODE_NAME}_$$.txt"
    echo "0" > "${COUNTER_FILE}_available"
    echo "0" > "${COUNTER_FILE}_total"
    
    # Initialise JSON array for capabilities
    if [ "$GENERATE_JSON" = true ]; then
        TEMP_JSON="/tmp/node_check_${SAFE_NODE_NAME}_$$.json"
        echo "[]" > "$TEMP_JSON"
    fi
    
    echo "Checking capabilities..."
    echo ""
    
    if [ "$USE_JQ" = true ]; then
        # Using jq for reliable JSON parsing
        echo "$CAPABILITIES_DATA" | jq -r '.capabilities[]? | "\(.capability_type)|\(.endpoint)|\(.version // "N/A")"' | while IFS='|' read -r capability_type endpoint version; do
            
            printf "  %-35s " "$capability_type"
            
            # Ping the endpoint (HTTP HEAD request with timeout)
            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 --head "$endpoint" 2>/dev/null)
            
            # Set default if empty
            HTTP_CODE="${HTTP_CODE:-000}"
            
            if [ "$HTTP_CODE" = "000" ]; then
                STATUS="Not available"
                COLOUR=$RED
            elif [ "$HTTP_CODE" = "404" ]; then
                STATUS="Not found"
                COLOUR=$YELLOW
            elif [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 400 ]; then
                STATUS="Available"
                COLOUR=$GREEN
                # Increment available counter
                CURRENT=$(cat "${COUNTER_FILE}_available")
                echo $((CURRENT + 1)) > "${COUNTER_FILE}_available"
            else
                STATUS="Not available"
                COLOUR=$RED
            fi
            
            echo -e "${COLOUR}${STATUS}${NC} (HTTP ${HTTP_CODE})"
            
            # Increment total counter
            CURRENT=$(cat "${COUNTER_FILE}_total")
            echo $((CURRENT + 1)) > "${COUNTER_FILE}_total"
            
            # Add to node text report
            if [ "$GENERATE_TEXT" = true ]; then
                printf "%-40s %s (HTTP %s)\n" "$capability_type" "$STATUS" "$HTTP_CODE" >> "$NODE_REPORT_TXT"
                printf "  └─ Endpoint: %s\n" "$endpoint" >> "$NODE_REPORT_TXT"
                printf "  └─ Version: %s\n\n" "$version" >> "$NODE_REPORT_TXT"
            fi
            
            # Add to JSON (use temp file due to subshell)
            if [ "$GENERATE_JSON" = true ]; then
                capability_escaped=$(echo "$capability_type" | sed 's/"/\\"/g')
                endpoint_escaped=$(echo "$endpoint" | sed 's/"/\\"/g')
                version_escaped=$(echo "$version" | sed 's/"/\\"/g')
                
                ENTRY=$(cat <<EOF
{
  "capability_type": "$capability_escaped",
  "endpoint": "$endpoint_escaped",
  "version": "$version_escaped",
  "status": "$STATUS",
  "http_code": "$HTTP_CODE"
}
EOF
)
                jq ". += [$ENTRY]" "$TEMP_JSON" > "${TEMP_JSON}.tmp" && mv "${TEMP_JSON}.tmp" "$TEMP_JSON"
            fi
        done
        
    else
        # Fallback: manual parsing without jq
        CAPABILITIES=$(echo "$CAPABILITIES_DATA" | sed -n '/"capabilities"/,/]/p')
        
        echo "$CAPABILITIES" | grep -E '"capability_type"|"endpoint"|"version"' | while read -r line; do
            if echo "$line" | grep -q '"capability_type"'; then
                capability_type=$(echo "$line" | sed 's/.*"capability_type"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')
                read -r line
                endpoint=$(echo "$line" | sed 's/.*"endpoint"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')
                read -r line
                if echo "$line" | grep -q '"version"'; then
                    version=$(echo "$line" | sed 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/')
                else
                    version="N/A"
                fi
                
                printf "  %-35s " "$capability_type"
                
                # Ping the endpoint
                HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 --head "$endpoint" 2>/dev/null)
                
                # Set default if empty
                HTTP_CODE="${HTTP_CODE:-000}"
                
                if [ "$HTTP_CODE" = "000" ]; then
                    STATUS="Not available"
                    COLOUR=$RED
                elif [ "$HTTP_CODE" = "404" ]; then
                    STATUS="Not found"
                    COLOUR=$YELLOW
                elif [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 400 ]; then
                    STATUS="Available"
                    COLOUR=$GREEN
                    # Increment available counter
                    CURRENT=$(cat "${COUNTER_FILE}_available")
                    echo $((CURRENT + 1)) > "${COUNTER_FILE}_available"
                else
                    STATUS="Not available"
                    COLOUR=$RED
                fi
                
                echo -e "${COLOUR}${STATUS}${NC} (HTTP ${HTTP_CODE})"
                
                # Increment total counter
                CURRENT=$(cat "${COUNTER_FILE}_total")
                echo $((CURRENT + 1)) > "${COUNTER_FILE}_total"
                
                # Add to node text report
                if [ "$GENERATE_TEXT" = true ]; then
                    printf "%-40s %s (HTTP %s)\n" "$capability_type" "$STATUS" "$HTTP_CODE" >> "$NODE_REPORT_TXT"
                    printf "  └─ Endpoint: %s\n" "$endpoint" >> "$NODE_REPORT_TXT"
                    printf "  └─ Version: %s\n\n" "$version" >> "$NODE_REPORT_TXT"
                fi
                
                # Add to JSON
                if [ "$GENERATE_JSON" = true ]; then
                    capability_escaped=$(echo "$capability_type" | sed 's/"/\\"/g')
                    endpoint_escaped=$(echo "$endpoint" | sed 's/"/\\"/g')
                    version_escaped=$(echo "$version" | sed 's/"/\\"/g')
                    
                    CURRENT=$(cat "$TEMP_JSON")
                    if [ "$CURRENT" = "[]" ]; then
                        cat > "$TEMP_JSON" <<EOF
[{
  "capability_type": "$capability_escaped",
  "endpoint": "$endpoint_escaped",
  "version": "$version_escaped",
  "status": "$STATUS",
  "http_code": "$HTTP_CODE"
}]
EOF
                    else
                        sed -i '$ d' "$TEMP_JSON" 2>/dev/null || sed -i.bak '$ d' "$TEMP_JSON"
                        cat >> "$TEMP_JSON" <<EOF
,{
  "capability_type": "$capability_escaped",
  "endpoint": "$endpoint_escaped",
  "version": "$version_escaped",
  "status": "$STATUS",
  "http_code": "$HTTP_CODE"
}]
EOF
                    fi
                fi
            fi
        done
    fi
    
    # Read final counter values
    TOTAL_CAPABILITIES=$(cat "${COUNTER_FILE}_total" 2>/dev/null || echo "0")
    AVAILABLE_CAPABILITIES=$(cat "${COUNTER_FILE}_available" 2>/dev/null || echo "0")
    
    # Clean up counter files
    rm -f "${COUNTER_FILE}_available" "${COUNTER_FILE}_total"
    
    # Finalise node JSON report
    if [ "$GENERATE_JSON" = true ] && [ -f "$TEMP_JSON" ]; then
        cat > "$NODE_REPORT_JSON" <<EOF
{
  "generated": "$(date -Iseconds)",
  "node_name": "$NODE_NAME",
  "node_id": "$NODE_ID",
  "node_pid": "$NODE_PID",
  "node_endpoint": "$NODE_ENDPOINT",
  "legal_entity": {
    "name": "$LEGAL_ENTITY_NAME",
    "ror_id": "$LEGAL_ENTITY_ROR"
  },
  "total_capabilities": $TOTAL_CAPABILITIES,
  "available_capabilities": $AVAILABLE_CAPABILITIES,
  "capabilities": $(cat "$TEMP_JSON")
}
EOF
        rm -f "$TEMP_JSON"
    fi
    
    echo ""
    echo "Node reports generated:"
    if [ "$GENERATE_TEXT" = true ]; then
        echo "  Text: $NODE_REPORT_TXT"
    fi
    if [ "$GENERATE_JSON" = true ]; then
        echo "  JSON: $NODE_REPORT_JSON"
    fi
    echo ""
    
    # Add to summary
    if [ "$GENERATE_TEXT" = true ]; then
        {
            echo "Node: $NODE_NAME"
            echo "  Endpoint: $NODE_ENDPOINT"
            echo "  Total Capabilities: $TOTAL_CAPABILITIES"
            echo "  Available Capabilities: $AVAILABLE_CAPABILITIES"
            echo ""
        } >> "$SUMMARY_FILE_TXT"
    fi
    
    # Write node summary to temp file for JSON aggregation
    if [ "$GENERATE_JSON" = true ]; then
        SUMMARY_TEMP="/tmp/summary_nodes_$$.json"
        
        # Escape node name for JSON
        NODE_NAME_ESCAPED=$(echo "$NODE_NAME" | sed 's/"/\\"/g')
        NODE_ENDPOINT_ESCAPED=$(echo "$NODE_ENDPOINT" | sed 's/"/\\"/g')
        NODE_REPORT_ESCAPED=$(echo "$NODE_REPORT_JSON" | sed 's/"/\\"/g')
        
        # Append node entry to temp file
        cat >> "$SUMMARY_TEMP" <<EOF
{
  "name": "$NODE_NAME_ESCAPED",
  "endpoint": "$NODE_ENDPOINT_ESCAPED",
  "total_capabilities": $TOTAL_CAPABILITIES,
  "available_capabilities": $AVAILABLE_CAPABILITIES,
  "report_file": "$NODE_REPORT_ESCAPED"
}
EOF
        echo "," >> "$SUMMARY_TEMP"  # Add separator
    fi
}

# Process all nodes
echo "=========================================="
echo "Processing nodes from registry..."
echo "=========================================="
echo ""

# Initialize temp file for JSON summary
if [ "$GENERATE_JSON" = true ]; then
    SUMMARY_TEMP="/tmp/summary_nodes_$$.json"
    > "$SUMMARY_TEMP"  # Clear file
fi

if [ "$USE_JQ" = true ]; then
    # Process each node using jq
    NODE_COUNT=$(echo "$NODES_DATA" | jq 'length')
    
    for i in $(seq 0 $((NODE_COUNT - 1))); do
        NODE_NAME=$(echo "$NODES_DATA" | jq -r ".[$i].name")
        NODE_ID=$(echo "$NODES_DATA" | jq -r ".[$i].id")
        NODE_PID=$(echo "$NODES_DATA" | jq -r ".[$i].pid")
        NODE_ENDPOINT=$(echo "$NODES_DATA" | jq -r ".[$i].node_endpoint")
        NODE_LOGO=$(echo "$NODES_DATA" | jq -r ".[$i].logo")
        LEGAL_ENTITY_NAME=$(echo "$NODES_DATA" | jq -r ".[$i].legal_entity.name")
        LEGAL_ENTITY_ROR=$(echo "$NODES_DATA" | jq -r ".[$i].legal_entity.ror_id")
        
        check_node_capabilities "$NODE_NAME" "$NODE_ID" "$NODE_PID" "$NODE_ENDPOINT" "$NODE_LOGO" "$LEGAL_ENTITY_NAME" "$LEGAL_ENTITY_ROR"
    done
    
    # Build final summary JSON from temp file
    if [ "$GENERATE_JSON" = true ] && [ -f "$SUMMARY_TEMP" ] && [ -s "$SUMMARY_TEMP" ]; then
        # Remove trailing comma from last entry
        sed -i '$ d' "$SUMMARY_TEMP" 2>/dev/null || sed -i.bak '$ d' "$SUMMARY_TEMP"
        
        # Build JSON array
        JSON_ARRAY="["
        JSON_ARRAY="${JSON_ARRAY}$(cat "$SUMMARY_TEMP")"
        JSON_ARRAY="${JSON_ARRAY}]"
        
        cat > "$SUMMARY_FILE_JSON" <<EOF
{
  "generated": "$(date -Iseconds)",
  "registry_source": "$NODE_REGISTRY_URL",
  "nodes": $JSON_ARRAY
}
EOF
        rm -f "$SUMMARY_TEMP" "${SUMMARY_TEMP}.bak"
    fi
    
else
    # Fallback: manual parsing
    echo "$NODES_DATA" | grep -o '"name":"[^"]*"' | sed 's/"name":"\([^"]*\)"/\1/' | while read -r NODE_NAME; do
        # Extract corresponding data (basic parsing - may be fragile)
        NODE_BLOCK=$(echo "$NODES_DATA" | grep -A 10 "\"name\":\"$NODE_NAME\"")
        NODE_ID=$(echo "$NODE_BLOCK" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"\([^"]*\)"/\1/')
        NODE_PID=$(echo "$NODE_BLOCK" | grep -o '"pid":"[^"]*"' | head -1 | sed 's/"pid":"\([^"]*\)"/\1/')
        NODE_ENDPOINT=$(echo "$NODE_BLOCK" | grep -o '"node_endpoint":"[^"]*"' | sed 's/"node_endpoint":"\([^"]*\)"/\1/')
        NODE_LOGO=$(echo "$NODE_BLOCK" | grep -o '"logo":"[^"]*"' | sed 's/"logo":"\([^"]*\)"/\1/')
        
        check_node_capabilities "$NODE_NAME" "$NODE_ID" "$NODE_PID" "$NODE_ENDPOINT" "$NODE_LOGO" "N/A" "N/A"
    done
    
    # Build final summary JSON from temp file
    if [ "$GENERATE_JSON" = true ] && [ -f "$SUMMARY_TEMP" ] && [ -s "$SUMMARY_TEMP" ]; then
        # Remove trailing comma from last entry
        sed -i '$ d' "$SUMMARY_TEMP" 2>/dev/null || sed -i.bak '$ d' "$SUMMARY_TEMP"
        
        JSON_ARRAY="["
        JSON_ARRAY="${JSON_ARRAY}$(cat "$SUMMARY_TEMP")"
        JSON_ARRAY="${JSON_ARRAY}]"
        
        cat > "$SUMMARY_FILE_JSON" <<EOF
{
  "generated": "$(date -Iseconds)",
  "registry_source": "$NODE_REGISTRY_URL",
  "nodes": $JSON_ARRAY
}
EOF
        rm -f "$SUMMARY_TEMP" "${SUMMARY_TEMP}.bak"
    fi
fi

echo "=========================================="
echo "All nodes processed!"
echo "=========================================="
echo ""
echo "Summary reports:"
if [ "$GENERATE_TEXT" = true ]; then
    echo "  Text: $SUMMARY_FILE_TXT"
fi
if [ "$GENERATE_JSON" = true ]; then
    echo "  JSON: $SUMMARY_FILE_JSON"
fi
echo "=========================================="