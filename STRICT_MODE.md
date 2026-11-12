# Strict Mode

The checks can run in two modes.

| | Lenient (the default) | Strict |
| - | --------------------- | ------ |
| URLs | completed, retried with another method, or replaced by alternatives, to get an answer wherever possible | used exactly as the node published them |
| Fallbacks | other endpoints, the Sandbox, last-known data | none |
| What counts as wrong | whatever stops the check from working | anything that differs from the node's own declarations or from the EOSC Node Endpoint model |
| Result | statuses and a few notes | statuses **and a list of findings** saying what was asked, what was expected, what came back, and what would help |

Lenient mode is for monitoring: it should keep working when a node is a little untidy. Strict mode is for
conformance: it reports the untidiness.

> **Status.** Strict mode covers **Node Capabilities**, **Exchange Services**, **Service Monitoring** and **Core
> Service integrations**. **Federated Search** cannot be strict as it stands: a Front Office capability publishes a
> web address, not a search API, so there is no URL to take "as published". The Node page therefore shows its stored
> result in strict mode as well, saying that it is not a strict result.

## Choosing the mode

The **Mode** menu in the top bar of the index and Node pages, *Lenient* (the default) or *Strict*, does two things:

- **The pages show the stored results of that mode.** Changing the mode does not run anything and never hides the
  other mode's results: change it back and they are there. If the chosen mode has no results yet, the page says so
  and says how to create them (**Run All Node Checks** on the Node page, **Check All** on the index page).
- **The checks run in that mode.** The *Run checks* menus have no mode choice of their own.

The choice is remembered in the browser.

## Running strict mode

- **Dashboard.** With *Strict* chosen, every check in the *Run checks* menu runs strictly except *Federated Search*.
  On the Node page, *Run All Node Checks* then leaves Federated Search out, rather than quietly giving a lenient
  result for it. The strict checks that read a node's endpoints (Exchange Services, Service Monitoring) need the node's
  **strict** `endpoint_report.json`, which only a strict *Node Capabilities* run writes, and that run covers every node
  in the registry. *Run All Node Checks* therefore runs it first when the node has no report of the chosen mode yet
  (saying so, as it takes longer), in lenient mode as well as in strict mode. The Node page menu also has a *Node
  Capabilities* item. A strict check that fails for want of the strict report says where to run Node Capabilities.
- **REST.** `POST /api/run/node-capabilities`, `POST /api/run/catalogue-services`, `POST /api/run/service-uptime`,
  `POST /api/run/core-integrations` and `POST /api/run/check-all` take an optional JSON body with `"mode": "strict"`
  (or `"lenient"`, the default); an unknown mode is a `400`. A strict `catalogue-services` request takes no
  `catalogueUrl`, because a strict run reads the endpoints the node published. A job's `type` says `(strict)` when it
  was a strict run.
- **Scheduled runs.** `check.check-all.scheduled.mode` (`lenient`, the default, or `strict`) sets the mode of the
  scheduled Check All. It is one mode or the other: to keep both up to date, run the other by hand or from outside.
- **Check All** in strict mode runs Node Capabilities and then, for every node, Exchange Services, Service Monitoring
  and Core Service integrations (not Federated Search). It reads and writes
  each node's own folder, and its summary says which checks were not run.

## Seeing the results

- **Node page.** In strict mode the page has the same panels as in lenient mode, in the same order and format,
  built from the node's strict reports: the summary strip (endpoint availability, Exchange Services, uptime and
  compliance tier), Node Capabilities with Core Service integrations, Exchange Services, Service Monitoring and
  Federated Search. Where a strict check has not been run, its panel is there and says so.
  Each panel ends with the strict result of the check or checks behind it: one line with Pass or Fail and the
  counts of errors, warnings and notes. A line opens to show every finding of that check, most serious first.
  A finding is a line with its severity, code and message, and a *Show details* link: it opens to show **what to
  change** in the node's Node Endpoint (see [Findings](#findings)), what was looked at, the URL, what was
  expected, what was found and any hint. Under the line are the answers that were checked against the `api_spec`
  (see below). Text that came from the node is shown as text, never as markup.
- **Federated Search** is shown from its stored (lenient) result, with a note saying so.
- **Index page.** In strict mode each node card shows a row for each strict check (Pass or Fail, with the counts, or
  *Not run*) and the summary bar shows the totals: nodes, strict pass, strict fail, not run, errors and warnings. In
  lenient mode the page is as it always was.
- The pages read each node's reports of the chosen mode (see below); running a check never changes the other mode's.

## Where the results go

A node's reports are kept together, and the two modes never overwrite each other:

```text
dashboard/data/node_registry_summary.json                   the list of nodes, once, for both modes
dashboard/data/<NODE>/endpoint_report.json                  strict
dashboard/data/<NODE>/catalogue_services_report.json        strict
dashboard/data/<NODE>/lenient/endpoint_report.json          lenient
dashboard/data/<NODE>/lenient/catalogue_services_report.json  lenient
...
```

- **Strict is the node's own folder** because it is meant to become the default, and **lenient has a folder of its
  own** so that it can be dropped when it is no longer wanted (delete the `lenient` folders; nothing else refers to
  them).
- **`node_registry_summary.json` exists once.** It lists the nodes the Node Registry gives (name, id, PID, endpoint,
  logo, legal entity) and nothing that a check found, so it is the same whichever mode ran. What a mode found for a
  node is in that node's reports.
- Every strict report says `"mode": "strict"`. That is how a strict report is told from a lenient report written by
  an earlier version of the dashboard, which kept lenient reports in `<NODE>/` (see below).
- A strict Exchange Services, Service Monitoring or Core Service integrations run reads the **strict**
  `endpoint_report.json`, so Node Capabilities has to be run in strict mode first.

### Moving from the earlier layout

Earlier versions wrote lenient reports to `<NODE>/` and (in preview) strict ones to `strict/<NODE>/`. Nothing needs
to be moved by hand:

- Until a lenient run has written to `<NODE>/lenient/`, the dashboard and the lenient checks use the lenient file
  that is in `<NODE>/`, if it does not say it is strict. The scheduled lenient Check All populates the `lenient`
  folders (overnight, if it is enabled).
- A strict run writes to `<NODE>/` and so replaces those earlier lenient files. A report in `<NODE>/` that does not say
  `"mode": "strict"` is never taken for a strict one.
- The old `dashboard/data/strict/` folder is no longer read or written and can be deleted. So can an old
  `node_registry_summary.txt` or any other file the new layout no longer mentions.

## Findings

Every strict report has `findings`, counts per severity, and `strict_result`:

```json
{
  "mode": "strict",
  "strict_result": "FAIL",
  "findings_error": 1, "findings_warning": 2, "findings_info": 0,
  "findings": [
    { "severity": "ERROR", "code": "endpoint-http-error",
      "message": "GET https://node.example.org/api did not succeed: HTTP 404",
      "subject": "Catalogue https://node.example.org/api",
      "url": "https://node.example.org/api",
      "expected": "a 2xx answer to GET https://node.example.org/api",
      "actual": "HTTP 404",
      "hint": "Lenient mode would request https://node.example.org/api/api/service/all instead of the URL as published",
      "fix": "Make the endpoint answer with HTTP 200. Check the address in your Node Endpoint and the service behind it." }
  ]
}
```

| Severity | Meaning | Effect |
| -------- | ------- | ------ |
| ERROR | the node does not conform, or something it publishes does not work | `strict_result` is `FAIL` |
| WARNING | accepted, but not as recommended, or something the check could not use | none |
| INFO | worth knowing | none |

A failure that the node itself declares (for example a capability whose `status` is `MAINTENANCE` that does not
answer) is an INFO, not an error.

### What to change

A node needs to know what to change in its Node Endpoint, so a finding also has a `fix`: a sentence of guidance for
the node, shown as **What to change** at the top of the finding's details. It is chosen by the finding's `code` from
a list in the source (`Guidance`), and is absent when there is nothing for the node to change (for example when the
fault is the federation's or the dashboard's). `expected`, `actual` and `hint` say what the check saw; `fix` says
what to do about it.

## Node Capabilities

The document is checked against the **EOSC Node Endpoint capabilities model**
(<https://github.com/madgeek-arc/eosc-node-endpoint>, module `eosc-node-capabilities-model`). There is no
machine-actionable specification and nodes publish different releases of the model, so the dashboard does not keep a
list of its fields: a field it does not know, such as `id`, is accepted and not reported.

- The node's `node_endpoint` is taken from the Node Registry exactly as given. It must be an absolute http(s) URL.
- The document is requested with `GET`. A failed request is repeated, up to three attempts, as in lenient mode, and
  a document that only arrived after a repeat gets a `request-retried` warning. If it cannot be read, the report is
  empty with an `error`: earlier data is **not** kept.
- The model has **one `endpoint` per capability**. A capability that lists its interfaces in an `endpoints` array
  is not expanded: it has no endpoint (error `endpoint-missing`, with a hint to list each interface as its own
  capability).
- `protocol` and `status` are extensible strings. Values the model recommends (`REST`, `SOAP`, `gRPC`, `SSE`,
  `WebSocket`, `RSocket`; `OPERATIONAL`, `MAINTENANCE`, `UNAVAILABLE`) pass; others such as `OAI-PMH` or `PLANNED`
  are warnings. An absent protocol or status means `REST` and `OPERATIONAL`.
- Each endpoint is requested **once, with `GET`**; there is no `HEAD` first. A 2xx answer is `Available`, and its
  body (up to 5 MB) is checked against the `api_spec` (see [below](#checking-the-answer-against-the-api_spec)). A redirect
  that was followed is a warning (`endpoint-redirected`); one that was not followed, such as a redirect from https
  to http, names its target without the query string (`endpoint-redirect-not-followed`).
- `api_spec` is fetched once per distinct URL. A machine-readable OpenAPI document is noted; a documentation page is
  a warning (`api-spec-not-machine-readable`) with advice to point at the specification itself; a URL that cannot
  be read is an error. The capability's `version` is compared with the document's `info.version` and a difference
  is an INFO, because the model does not say whether they must match.

## Exchange Services

The capability types and their order are those of lenient mode (`check.catalogue.capability-order`). Differences:

- Each candidate endpoint is requested **exactly as published**, with `GET`. Nothing is appended: a Beyond API
  base such as `https://host/api` is not turned into `…/api/service/all`. If it does not return a service list, the
  finding says what was requested, what came back, and (hint) what lenient mode would have asked for.
- Candidates are tried in order until one returns a list the check can read. Later ones are not requested, so a
  Resource Catalogue of research products is not reported on when the Service Catalogue works.
- A format the check cannot read (a DCAT feed, for example) is a **warning**; it becomes an error
  (`no-readable-catalogue`) only if nothing could be read at all. Endpoints that fail (HTTP errors, no connection)
  are errors.
- A node that declares a catalogue capability but gives it no endpoint fails (`catalogue-endpoint-missing`); a node
  with no catalogue capability at all is skipped.
- **`api_spec` and `version` are used to check the response.** An `api_spec` that names a release of the EOSC-Lot-1
  model (a URL under `EOSC-Lot-1/eosc-service-catalogues-specs/<tag>/`) means the response must be that release:
  a match is an INFO, a difference an error (`format-differs-from-api-spec`). An absent or unrecognised `api_spec`
  is noted and the format is taken from the content. A `version` that looks like a Lot-1 release number but differs
  from what came back is an INFO.
- Lot-1 records are validated against the bundled model; each record that fails is an error (`record-invalid`).
  After the first response, further pages are read with the `from` and `quantity` parameters the model defines, which
  is noted.
- A list in another format that reports more services than the response holds is a warning (`list-incomplete`),
  because paging is only followed for Lot-1.
- There is **no fallback to the Sandbox**.

## Checking the answer against the `api_spec`

When a capability publishes an `api_spec` that is an OpenAPI document (JSON, OpenAPI 3.x or Swagger 2.0), the answer
the endpoint gave is checked against it, and the result is shown. Otherwise the report says the payload **was not
checked**, and why.

- **Node Capabilities** checks the answer of every capability's endpoint (the same `GET` that decides whether it is
  available). Each row of the strict `endpoint_report.json` has a `payload_check`.
- **Exchange Services** checks the first response of the catalogue endpoint it read, against the Lot-1 release the
  `api_spec` names (as before) or, for any other `api_spec`, against the OpenAPI document. The result is
  `source.payload_check` in the strict `catalogue_services_report.json`.
- **How the check works.** The operation is found from the endpoint's URL: its path, under the specification's
  `servers` (or `basePath`), matched as a `GET` against the specification's paths (an exact path wins over a template
  such as `/service/{id}`). The answer is validated against that operation's success response (`200`, else another
  `2xx`), taking the `application/json` schema. A `payload_check` is either **checked** (`PASS` or `FAIL`, the
  operation, the number of violations and the first ten) or **not checked**, with the reason:
  - no `api_spec` is published, or it is not an http(s) URL;
  - the endpoint did not answer successfully, or the answer is larger than 5 MB;
  - the `api_spec` could not be read as a JSON document (a documentation page, an error, or YAML, which is not read);
  - the `api_spec` is JSON but not an OpenAPI document;
  - the specification has no path for the endpoint, no `GET` for it, no success response, no JSON response or no
    schema for it;
  - the specification refers to another document (`$ref` outside itself), which is never fetched.
- A `FAIL` is an error finding (`payload-differs-from-api-spec`) that quotes the first violations; a pass is a note
  (`payload-matches-api-spec`). A payload that could not be checked adds no finding: it is recorded in the report, and
  the Node page lists every answer with *Follows api_spec*, *Breaks api_spec* or *Not checked*.
- An answer that is not JSON counts as breaking a specification that describes a JSON response.
- Formats (`uri`, `date-time`, ...) are not asserted, only types, required properties and the other structure.

## Service Monitoring

Only the Monitoring endpoint the node published, read from the **strict** `endpoint_report.json`, is used, exactly
as published. There is no fallback to the metrics API, the public dashboard or the legacy API, no guessing of the
ARGO tenant, no reading of a dashboard page to find the results, and no `apiKey`.

- The endpoint must be the ARGO capability-metrics API (it ends in `/capabilities/monitoring/metrics`). A dashboard
  page, or any other URL, is an error (`monitoring-endpoint-not-a-data-api`) that says what to publish instead and
  that lenient mode would have read the page. A node with no Monitoring capability or endpoint is an error
  (`monitoring-endpoint-missing`).
- The URL is requested **once, with `GET`, as published**. The one addition is the reporting period: a URL that
  names neither `start-date` nor `end-date` is requested with the last month added (as lenient mode does), because a
  fixed period cannot be kept up to date by hand. This is reported as a note (`monitoring-period-added`), and the
  report records `period_start` and `period_end`. A URL that names a period is used as published.
- An HTTP error or an unreachable endpoint (`endpoint-http-error`, `endpoint-unreachable`), an answer in the wrong
  shape (`monitoring-payload-unreadable`) and a list of no services (`monitoring-no-services`) are errors.
- The report is `<NODE>/argo_uptime_report.json`. A strict run needs the node's strict `endpoint_report.json`.

## Core Service integrations

The federation tenant's ARGO feed is read as configured (`check.argo-status-api-base`,
`check.argo-federation-tenant`), and:

- The node's group must be named **exactly** as the node (`node-not-monitored` otherwise, saying so when only the
  case differs: lenient mode ignores the case).
- Every endpoint's status is judged: `CRITICAL` is an error (`core-endpoint-critical`); `WARNING`, `UNKNOWN` and
  `MISSING` are warnings; an endpoint that is OK now but was not earlier today is a note
  (`core-endpoint-recovered`). Each finding names the capability type and URL, the status, the failing probes and the
  ARGO page for the node.
- A capability that the node declares as not operational (for example `UNAVAILABLE`) is judged against that: ARGO
  reporting it as not OK is a note (`declared-unavailable-confirmed`), and ARGO reporting it OK is a warning
  (`declared-unavailable-but-ok`).
- The monitored endpoints are compared with the node's strict `endpoint_report.json`: a monitored URL that differs
  from the declared one is a warning (`monitored-url-differs`), and a monitored capability the node does not declare
  is a note (`monitored-capability-not-declared`). The Monitoring capability is not compared: the node declares the
  metrics of its own Exchange services, whereas the federation monitors the Core Services the node uses, so the two
  URLs differ by design. Without a strict report the comparison is skipped, with a note.
- A feed that cannot be read is an error finding (`argo-feed-unavailable`), not an exception, so the report still
  says what happened.
- The report is `<NODE>/core_integrations_report.json`.

## What is not done yet

- Running both modes in one scheduled run (the scheduled run uses one mode, `check.check-all.scheduled.mode`).
- A strict Federated Search (see the status above).
- The index page's strict node cards list the strict checks (Node Capabilities, Exchange Services, Service
  Monitoring, Core Service integrations) rather than the categories of the lenient cards.
- Controlled vocabularies for `protocol` and `status`. Until there are some, the values the model recommends are kept
  in `CapabilitiesModel`; the model is at
  <https://github.com/madgeek-arc/eosc-node-endpoint/tree/main/eosc-node-capabilities-model>.

## For developers

### Source layout

The code of each category is in the package of that name under `eu.cessda.pilotnode`. Strict code and code that
both modes use are in the category package; the lenient code is in its `lenient` subpackage; what is shared across
categories is in `common`:

| Package | Strict and shared | `lenient/` |
| ------- | ----------------- | ---------- |
| `capabilities` | `StrictNodeCapabilities`, `CapabilitiesModel`, `CheckNodeCapabilities` (the run over the registry, which both modes use) | `LenientNodeCapabilities` |
| `catalogue` | `StrictCatalogueServices`, `StrictCatalogueSelector`, `CatalogueCandidates`, `CatalogueReport`, the format adapters | `CheckCatalogueServices`, `CatalogueSelector` |
| `monitoring` | `StrictServiceUptime`, `ArgoMetrics` | `CheckServiceUptime` |
| `core` | `StrictCoreIntegrations`, `ArgoStatus` | `CheckCoreIntegrations` |
| `federatedsearch` | | `CheckOtherMetrics` |
| `common` | `CheckMode`, `ReportFiles`, `Finding`, `Findings`, `Guidance`, `OpenApiPayloadCheck` | |

`CheckAll`, `CheckAllScheduler` and `CheckRunnerController` are in the root package: they choose the mode. The tests
are in the same packages.

### Writing a strict check

- Request what the node published, as published. Report what differs as a `Finding` (`Findings.error`, `warning`
  or `info`), with a stable `code`, and put the guidance for the node in `Guidance` under that code: an
  error or a warning should have an entry; `GuidanceTest` checks a sample of them.
- Write the report with `findings.writeTo(report, mapper)`, into `CheckMode.STRICT.nodeDir(dataDir, node)`. Read
  other reports with `ReportFiles`, which knows both layouts and never takes a lenient report for a strict one.
- The package structure is checked by `LenientIsRemovableTest`.

### Removing lenient mode

When lenient mode is no longer wanted:

1. Delete the `lenient` packages (and their tests) and the `lenient` folders under `dashboard/data/<NODE>/`.
2. Edit the four classes that choose a mode, the only ones allowed to refer to a `lenient` package:
   `CheckAll`, `CheckAllScheduler`, `CheckRunnerController` and `CheckNodeCapabilities`.
   `LenientIsRemovableTest` fails if any other class outside a `lenient` package refers to one.
3. Remove the Mode menu and the lenient panels from the static pages (`scripts.js`, `index.html`, `node.html`).

`CatalogueSelector` (lenient) is where `CheckAll` and the controller get the strict check's list of candidate
endpoints from today (`candidateSource()`); that is the one piece to move into `catalogue` when step 1 is done.
