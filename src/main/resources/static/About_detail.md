# EOSC Beyond Node Detail Page

## Overview

The Node Detail page shows all collected monitoring data for a
single pilot node. It presents four panels — endpoint connectivity,
catalogue services, ARGO uptime, and node metadata — together with
an overview strip summarising key metrics.

The page is addressed by appending the node name as a URL fragment,
for example `node.html#My-Node-Name`. The node switcher in the top
bar allows navigation between nodes without returning to the
dashboard.

## Overview Strip

The strip below the page header displays four summary values for
the selected node.

- **Endpoints available** — the number of capabilities whose status
  is Available, expressed as a fraction of the total reported (for
  example, 2/8).
- **Catalogue services** — the count of active services found in the
  Resource Catalogue report, or the total if no status field is
  present.
- **Avg ARGO uptime** — the mean uptime percentage across all
  services in the ARGO report.
- **Compliance tier** — the highest compliance tier fully satisfied
  by this node (see Compliance Tiers below).

## Compliance Tiers

The compliance tier represents the level of federation capability
the node has achieved. Tiers are cumulative: a node must fully
satisfy each tier before it can be credited with the next.

| Tier | Label    | Required capabilities                          |
|------|----------|------------------------------------------------|
| 1    | MVP      | AAI, Resource Catalogue                        |
| 2    | Standard | Helpdesk, Service Monitoring                           |
| 3    | Advanced | Service Accounting, Research Product           |
|      |          | Accounting, Order Management, Application      |
|      |          | Deployment Management                          |

The compliance tier shown in the overview strip uses colour coding:
amber for Tier 1 (MVP), orange for Tier 2 (Standard), and green for
Tier 3 (Advanced). A node that does not satisfy Tier 1 is shown in
red as Below MVP.

## Endpoint Connectivity Report

This panel shows the results of the most recent
`CheckNodeCapabilities` run for this node. The capabilities are
grouped by compliance tier so it is straightforward to see which
tier requirements are met and which are outstanding.

Within each tier section, a badge shows how many of that tier's
capabilities are currently Available. Each capability card displays:

- The capability type name.
- The endpoint URL that was tested.
- The status (Available or the error returned) and HTTP response
  code.
- The version string reported by the endpoint, if present.

Capability types that are not present in the report at all are shown
as greyed-out Not reported cards so that gaps are visible rather
than simply absent.

Any capabilities in the report that do not belong to a defined tier
are shown in an Other capabilities section below the three tier
groups.

The data comes from:

```text
/api/data/{node_name}/endpoint_report.json
```

## Catalogue Services Report

This panel lists the services published in the node's Resource
Catalogue. Each row in the table shows the service name, its status,
and a link to its catalogue entry or landing page where available.

The Resource Catalogue endpoint URL is read from the node's
`endpoint_report.json` and then queried directly. If the endpoint
report is absent or does not contain a Resource Catalogue capability,
this panel will show a not-available message.

The data comes from:

```text
/api/data/{node_name}/catalogue_services_report.json
```

## ARGO Uptime Report

This panel shows service availability as measured by the ARGO
monitoring infrastructure. Each service card displays an uptime
percentage and a proportional bar coloured green (≥ 90 %), amber
(≥ 60 %), or red (below 60 %).

The data comes from:

```text
/api/data/{node_name}/argo_uptime_report.json
```

## Node Metadata

This panel displays descriptive information about the node sourced
from the node registry, including its country, organisation,
contact details, and endpoint URL.

## Running Checks

The **Run checks** menu in the top bar triggers on-demand data
collection for the currently selected node. The available checks are:

- **Catalogue Services** — runs `CheckCatalogueServices` and
  refreshes `catalogue_services_report.json` for this node.
- **Service Uptime** — runs `CheckServiceUptime` and refreshes
  `argo_uptime_report.json` for this node. An API key is required
  and must be entered in the prompt that appears; it is used only
  for that single request and is never stored.

The page must be served from an HTTP server with an active backend
for the Run checks menu to work; it will not function when the file
is opened directly from disk.
