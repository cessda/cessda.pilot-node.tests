# EOSC Beyond Node Detail Page

## Overview

The Node Detail page shows all collected monitoring data for a
single pilot node. It presents four panels — endpoint connectivity,
catalogue services, ARGO uptime, and Federated Search — together with
an overview strip summarising key metrics and a page header carrying
the node's registry status and contact metadata.

The page is addressed by appending the node name as a URL fragment,
for example `node.html#My-Node-Name`. The node switcher in the top
bar allows navigation between nodes without returning to the
dashboard.

## Header

The page header shows the selected node's name alongside:

- **Registry** — a chip confirming the node's presence in the Node
  Registry (evidence for the Core compliance dimension's C1
  indicator "Present in Node Registry"), shown as Registered,
  Not registered, or Registered — checking… while registry data is
  still loading.
- **Provider**, **Endpoint**, and **PID**, where reported.

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

The strip does not currently summarise Federated Search visibility;
that panel carries its own summary badge instead (see below).

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
Catalogue. Each row shows the service name, a link to its catalogue
entry or landing page, and — where the service has a webpage —
the results of an automated health check against it (Metric 13 in
the Proposed Validation Metrics document):

- **Response** — the time taken to fetch the webpage, in
  milliseconds.
- **Content** — the response's content type, with a ✓ or ✕
  indicating whether the body passed validation (a non-empty,
  parseable JSON body for API-style content types; a non-empty body
  otherwise).
- **Status** — Available for a full pass; a qualified
  Available (…) status such as Available (content check failed) or
  Available (slow: …ms) for a service that responds but doesn't
  fully pass; Not found or No webpage defined where nothing was
  checked; otherwise Not available.

The panel header shows a healthy/total count (for example, 7/9
healthy), coloured green, amber, or red, plus the average response
time across all checked services. Reports written before this
enhancement — with no per-service response time or content data —
still display correctly: the header falls back to a plain service
count, and each row shows an em dash in place of the missing detail.

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
monitoring infrastructure. Each service card shows Availability,
Reliability, and Uptime as three separate percentages, each with its
own proportional bar coloured green (≥ 90 %), amber (≥ 60 %), or red
(below 60 %), plus how long the service was monitored for and the
reporting period.

The "monitored for" period is shown in months when the report came
from the default capability-metrics API (which reports monthly), or
in days for the dashboard-scrape and legacy-API fallback sources.

The data comes from:

```text
/api/data/{node_name}/argo_uptime_report.json
```

## Federated Search Report

This panel covers the cross-node visibility metrics from the
Proposed Validation Metrics document — Metrics 4, 5, 6, 9, 10 and
11 — which together evidence the Exchange (E) maturity dimension,
distinct from the Core compliance tiers above.

Each metric renders as its own card, in numerical order:

- **Metric 4** — this node's own resources appear in its own Front
  Office.
- **Metric 5** — an Exchange service from another networked Pilot
  Node is visible in this node's Front Office. Shown as a visible
  peers / total peers summary behind an expandable detail list,
  since the result is per peer node.
- **Metric 6** — one or more of this node's Exchange services are
  visible and accessible from the Sandbox Front Office.
- **Metric 9** — at least one of this node's Research Outputs is
  visible in the Sandbox Discovery Hub.
- **Metric 10** — rendered as an alias of Metric 4 rather than
  queried separately: the two metrics were only distinct while
  services were onboarded centrally and propagated out to each
  node's Front Office; now that onboarding is local to each node,
  they are mechanically identical.
- **Metric 11** — this node's own Exchange service is visible in
  another networked Pilot Node's Front Office. Shown the same way
  as Metric 5, per peer node.

The panel header shows a count of visible metrics out of all six
cards shown below it, Metric 10's alias card included — its
"visible" value simply mirrors Metric 4's, so the header total
always matches the number of cards on screen.

The data comes from:

```text
/api/data/{node_name}/front_office_metrics_report.json
```

## Running Checks

The **Run checks** menu in the top bar triggers on-demand data
collection for the currently selected node. None of the checks
require any credentials to be entered. The available checks are:

- **Exchange Services** — runs `CheckCatalogueServices` and
  refreshes `catalogue_services_report.json` for this node, using the
  Resource Catalogue endpoint read from `endpoint_report.json`.
- **Service Monitoring** — runs `CheckServiceUptime` and refreshes
  `argo_uptime_report.json` for this node against the public ARGO
  capability-metrics API (falling back to a dashboard scrape, then a
  legacy API, if needed) — no API key prompt, since the default
  source is public.
- **Federated Search** — runs `CheckOtherMetrics` and refreshes
  `front_office_metrics_report.json` for this node. No credentials or
  additional input are required: the check resolves every PID and
  Front Office endpoint it needs, including the Sandbox's, from data
  already collected by Node Capabilities.

The page must be served from an HTTP server with an active backend
for the Run checks menu to work; it will not function when the file
is opened directly from disk.
