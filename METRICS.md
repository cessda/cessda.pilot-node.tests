# Metrics

This should be read in conjunction with [Proposed Validation Metrics for Pilot Nodes](https://docs.google.com/document/d/1Kr7d93Rj4D1OyXJP1WxJK8JgZ6gAm46-26oZ1NDCTS8/edit?usp=sharing).

Each automated metric below is covered by one of the Java checks in
`src/main/java/eu/cessda/pilotnode/`. Every check can be run from the
command line or triggered from the dashboard UI — see
[Dashboard](DASHBOARD_README.md) for the UI names, endpoints, and the
**Check All** job that runs everything below for every node in one go. None
of them require an access token or API key any more except
`CheckNodeCapabilities`, which needs the Node Registry API key described
there.

## Metrics 1 to 3

These currently require manual intervention.

## Metrics 4, 5, 6, 9, 10, and 11

Use [`CheckOtherMetrics`](DASHBOARD_README.md#checkothermetrics-federated-search)
(Federated Search on the dashboard):

```text
CheckOtherMetrics NODE_NAME [dashboard_dir]
```

It resolves every PID and Front Office endpoint it needs — including the
Sandbox's — from `node_registry_summary.json` and each node's
`endpoint_report.json`, so `CheckNodeCapabilities` must be run first (or
already be up to date). See the check's own Javadoc, or
[About_detail.md](src/main/resources/static/About_detail.md)'s Federated
Search Report section, for what each of the six metrics specifically
checks; in short:

- Metric 4 — the node's own resources appear in its own Front Office.
- Metric 5 — a peer node's Exchange service is visible in this node's
  Front Office.
- Metric 6 — this node's Exchange service is visible from the Sandbox
  Front Office.
- Metric 9 — this node's Research Output is visible in the Sandbox
  Discovery Hub.
- Metric 10 — reported as an alias of Metric 4 (not queried separately —
  see the check's Javadoc for why).
- Metric 11 — this node's Exchange service is visible in a peer node's
  Front Office.

## Metrics 7 to 8

These currently require manual intervention.

## Metric 12

Use [`CheckServiceUptime`](CHECK_SERVICE_UPTIME.md) (Service Monitoring on
the dashboard):

```text
CheckServiceUptime NODE_NAME [API_KEY] [START_DATE] [END_DATE] [dashboard_dir]
```

By default this queries ARGO's public capability-monitoring metrics API, so
no access token is needed. An `API_KEY` is only used as a last resort, if
that API and a public ARGO dashboard scrape are both unavailable, in which
case a legacy ARGO API access token is required — request one from the
ARGO Service Monitoring team.

## Metric 13

Use [`CheckCatalogueServices`](DASHBOARD_README.md#checkcatalogueservices-exchange-services)
(Exchange Services on the dashboard):

```text
CheckCatalogueServices NODE_NAME [api_base_url] [quantity] [dashboard_dir]
```

The per-service webpage check (HTTP status, response time, and
content-type/validity) that evidences this metric runs automatically as
part of this check for every service in the node's Resource Catalogue — no
separate check or access token is needed.
