# Check Catalogue Services

A Bash script that checks the availability of the Exchange services listed in
the Node's Service Catalogue and generates a JSON report.

## Features

- Fetches service data from the Node's Service Catalogue API
- Checks HTTP availability of each service's webpage
- Writes `catalogue_services_report.json` directly to the dashboard data
  directory
- Colour-coded terminal output
- Handles services without defined webpages
- Verbose debugging output for troubleshooting

## Requirements

- macOS (or Linux)
- `curl` (pre-installed on macOS)
- `jq` (recommended but optional)

### Installing jq (recommended)

```bash
brew install jq
```

The script will work without `jq` using basic grep/sed parsing, but `jq`
provides more reliable JSON handling.

## Usage

```bash
chmod +x check_catalogue_services.sh
./check_catalogue_services.sh NODE_NAME [format] [quantity] [dashboard_dir]
```

### Arguments

| Argument | Required | Default | Description |
| ---------- | -------- | ------- | ----------- |
| `NODE_NAME` | Yes | — | Node name used as the keyword filter and for the output directory |
| `format` | No | `both` | Output format: `text`, `json`, or `both` |
| `quantity` | No | `10` | Maximum number of services to retrieve |
| `dashboard_dir` | No | `../dashboard/data` | Path to dashboard data directory |

### Examples

```bash
# Write JSON report to the default dashboard data directory
./check_catalogue_services.sh CESSDA json

# Retrieve up to 20 services
./check_catalogue_services.sh CESSDA json 20

# Write to an explicit dashboard path
./check_catalogue_services.sh CESSDA json 10 /path/to/dashboard/data

# Write timestamped files to the current directory (no dashboard_dir)
./check_catalogue_services.sh CESSDA both 10
```

## Output Files

### With dashboard_dir supplied

The report is written to `<dashboard_dir>/<NODE_NAME>/catalogue_services_report.json`:

```text
dashboard/data/CESSDA/catalogue_services_report.json
```

### Without dashboard_dir

Timestamped files are written to the current directory:

```text
catalogue_services_report_CESSDA_20260218_163045.json
catalogue_services_report_CESSDA_20260218_163045.txt
```

## Status Categories

The script categorises service webpages into four status types:

- Available: HTTP status code 200–399 (shown in green)
- Not found: HTTP status code 404 (shown in yellow)
- Not available: Connection timeout or other HTTP errors (shown in red)
- No webpage defined: Service entry has no webpage field (shown in yellow)

## Example Output

### Terminal Display

```text
======================================
Service Catalogue Resource Availability Report
======================================
Generated: Wed Feb 18 16:30:45 UTC 2026
Node Name: CESSDA
API Source: https://service-catalogue-staging.beyond.cessda.eu/api/service/all?keyword=CESSDA&from=0&quantity=10&order=asc
======================================

Fetching service data from API...

Data retrieved successfully!

Total services found: 7

Checking service webpages...

CESSDA Data Catalogue                             Available
CESSDA Vocabulary Service                         Available
CESSDA European Language Social Science Thesaurus Available
CESSDA Data Management Expert Guide               Available
CESSDA Data Archiving Guide                       Available

======================================
Report generated:
  JSON: ../dashboard/data/CESSDA/catalogue_services_report.json
======================================
```

### JSON Report Format

```json
{
  "generated": "2026-02-18T16:30:45+00:00",
  "node_name": "CESSDA",
  "api_source": "https://service-catalogue-staging.beyond.cessda.eu/api/service/all?keyword=CESSDA&from=0&quantity=10&order=asc",
  "total_services": 7,
  "services": [
    {
      "name": "CESSDA Data Catalogue",
      "abbreviation": "CDC",
      "service_id": "21.15132/2shDkg",
      "webpage": "https://www.cessda.eu/Tools/Data-Catalogue",
      "status": "Available",
      "http_code": "200"
    }
  ]
}
```

## API Endpoint

The script queries:

```text
https://service-catalogue-staging.beyond.cessda.eu/api/service/all?keyword=NODE_NAME&from=0&quantity=QUANTITY&order=asc
```

Query parameters:

- `keyword=NODE_NAME`: Filter by node name keyword (URL-encoded internally;
  multi-word names are supported)
- `from=0`: Start from the first result
- `quantity=QUANTITY`: Maximum number of results to return
- `order=asc`: Ascending order

You can modify the `API_BASE_URL` variable in the script if you need to query a
different endpoint, for example the Sandbox Resource Catalogue:

```text
https://providers.sandbox.eosc-beyond.eu/api/service/all
```

## Data Extracted

From each service in the API response, the script extracts:

- `name`: Service name
- `webpage`: Service webpage URL
- `id`: Service identifier
- `abbreviation`: Service abbreviation (if available)

## Troubleshooting

### Debug Mode

The script runs with verbose `curl` output enabled, showing all HTTP headers and
connection details. This helps diagnose connection issues.

What you will see in the terminal:

- Full HTTP request headers
- Server response headers
- HTTP status code
- Connection details
- First 500 characters of the JSON response

### Temporary Debug Files

The script creates temporary files that can help with troubleshooting:

- `/tmp/curl_output_$$.log` — verbose curl output (connection details)
- `/tmp/curl_response_$$.json` — raw JSON response from the services API

These files persist after the script runs and can be examined if needed.

```bash
# Inspect the raw API response
cat /tmp/curl_response_*.json | python -m json.tool | head -50
```

### Common Issues

#### "ERROR: Failed to fetch data from API"

- Check your network connection
- Verify the API endpoint is accessible

#### "ERROR: Response is not valid JSON"

- The API may be returning an HTML error page
- Check the HTTP status code in the verbose output (it should be 200)

#### "WARNING: API response contains error field"

- This may be a false positive if "error" is merely a field name in the response
- Check the JSON response shown in the output to verify
- The script will continue processing unless it is a critical error
