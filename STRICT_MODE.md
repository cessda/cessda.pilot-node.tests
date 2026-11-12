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

> **Status.** Strict mode covers **Node Capabilities** and **Exchange Services**. **Service Monitoring** and **Core
> Service integrations** have no strict mode yet. **Federated Search** cannot be strict as it stands: a Front Office
> capability publishes a web address, not a search API, so there is no URL to take "as published". The Node page
> is therefore always a lenient result.

## Running strict mode

- **Dashboard.** The *Run checks* menu has a **Mode** choice, *Lenient* (the default) or *Strict*, remembered in the browser. With *Strict* chosen, *Node Capabilities* and *Exchange Services* run strictly; the other checks have
  no strict mode and would run leniently. On the Node page, *Run All Node Checks* therefore runs only Exchange
  Services in strict mode, rather than quietly giving lenient results for the others. The strict check that reads a node's endpoints (Exchange Services) needs the node's
  **strict** `endpoint_report.json`, which only a strict *Node Capabilities* run writes, and that run covers every node
  in the registry. *Run All Node Checks* therefore runs it first when the node has no report of the chosen mode yet
  (saying so, as it takes longer), in lenient mode as well as in strict mode. The Node page menu also has a *Node
  Capabilities* item. A strict check that fails for want of the strict report says where to run Node Capabilities.
- **REST.** `POST /api/run/node-capabilities`, `POST /api/run/catalogue-services` and `POST /api/run/check-all` take an optional JSON body with `"mode": "strict"`
  (or `"lenient"`, the default); an unknown mode is a `400`. A strict `catalogue-services` request takes no
  `catalogueUrl`, because a strict run reads the endpoints the node published. A job's `type` says `(strict)` when it
  was a strict run.
- **Check All** in strict mode runs Node Capabilities and then Exchange Services for every node (the other checks have
  no strict mode). It reads and writes each node's own folder, and its summary says which checks were not run.

## Seeing the results

The results are the strict reports of each node (see below), which can be read from `/api/data/<NODE>/`. The
dashboard pages do not show them yet; the lenient results are shown as before.

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
- A strict Exchange Services run reads the **strict**
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
- Each endpoint is requested **once, with `GET`**; there is no `HEAD` first. A 2xx answer is `Available`. A redirect
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

## What is not done yet

- A strict Service Monitoring and Core Service integrations, and a strict Federated Search (see the status above).
- Showing the strict results on the Node page and the index page.
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
| `monitoring` | `ArgoMetrics` | `CheckServiceUptime` |
| `core` | `ArgoStatus` | `CheckCoreIntegrations` |
| `federatedsearch` | | `CheckOtherMetrics` |
| `common` | `CheckMode`, `ReportFiles`, `Finding`, `Findings`, `Guidance` | |

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
