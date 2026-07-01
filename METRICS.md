# Metrics

This should be read in conjunction with [Proposed Validation Metrics for Pilot Nodes](https://docs.google.com/document/d/1Kr7d93Rj4D1OyXJP1WxJK8JgZ6gAm46-26oZ1NDCTS8/edit?usp=sharing).

## Metrics 1 to 3

These currently require manual intervention.

## Metrics 4 to 6

Use [check_catalogue_services](./src/main/scripts/check_catalogue_services.sh)
with API_BASE_URL set to your Node's Resource Catalogue API for 4 and 5 and the
Sandbox Resource Catalogue API for metric 6.

## Metrics 7 to 8

These currently require manual intervention.

## Metric 9

This currently requires manual intervention.

## Metrics 10 to 11

Use [check_catalogue_services](./src/main/scripts/check_catalogue_services.sh)
with API_BASE_URL set to your Node's Resource Catalogue API for 10 and
the other Node's Resource Catalogue API for metric 11.

## Metric 12

Use [check_service_uptime](./src/main/scripts/check_service_uptime.sh).
You will need to request an ACCESS TOKEN from the ARGO Service Monitoring team.

## Metric 13

Use [check_endpoint_capabilities](./src/main/scripts/check_endpoint_capabilities.sh).
You will need to request an ACCESS TOKEN from GRNet.
