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

> **Status.** The strict core is in place for **Node Capabilities** and **Exchange Services**. It is not yet
> reachable from the dashboard's menus or REST interface, and the other checks (Service Monitoring, Core Service
> integrations, Federated Search) have no strict mode yet. Federated Search cannot be strict as it stands: a Front
> Office capability publishes a web address, not a search API, so there is no URL to take "as published".

## Where the results go

Strict results never replace lenient ones. They are written under a `strict` directory that mirrors the data
directory:

```text
dashboard/data/<NODE>/endpoint_report.json                  lenient
dashboard/data/strict/<NODE>/endpoint_report.json           strict
dashboard/data/strict/node_registry_summary.json            strict
```

Every report has a `mode` field. A strict Exchange Services run reads the **strict** `endpoint_report.json`, so
Node Capabilities has to be run in strict mode first.

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
      "hint": "Lenient mode would request https://node.example.org/api/api/service/all instead of the URL as published" }
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

## Node Capabilities

The document is checked against the **EOSC Node Endpoint capabilities model**, release 1.1.0
(<https://github.com/madgeek-arc/eosc-node-endpoint>, module `eosc-node-capabilities-model`).

- The node's `node_endpoint` is taken from the Node Registry exactly as given. It must be an absolute http(s) URL.
- The document is requested with `GET`. A failed request is repeated, up to three attempts, as in lenient mode, and
  a document that only arrived after a repeat gets a `request-retried` warning. If it cannot be read, the report is
  empty with an `error`: earlier data is **not** kept.
- The model has **one `endpoint` per capability**. A capability that lists its interfaces in an `endpoints` array
  is not expanded: it has no endpoint (error `endpoint-missing`, with a hint to list each interface as its own
  capability) and the extra field is a `field-not-in-model` warning.
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

- The mode toggle in the *Run checks* menu of the index and node pages, and the `mode` setting in the REST requests.
- Running both modes in the scheduled run.
- Showing the findings on the Node page and in the index summary.
- Strict versions of Service Monitoring and Core Service integrations.
- Checking payloads against an OpenAPI `api_spec` (only the EOSC-Lot-1 releases are checked so far).
