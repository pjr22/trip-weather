# Refactor TODO

## Preserve Timezone Metadata During Persistence
- Currently the front-end strips waypoint timezone fields when saving/loading routes (`RoutePersistenceService.convertWaypointsToDto/FromDto`).
- Proper fix requires extending `WaypointDto`/server responses to include timezone identifiers, offsets, and abbreviations.
- Coordinate with backend before adjusting the client serializers to avoid breaking the existing API contract.

## Consolidate Map Geolocation Flows
- `MapManager.initializeWithUserLocation` and `MapManager.recenterOnUserLocation` duplicate the same geolocation + overlay logic.
- Refactor into a shared helper to keep permission handling, error reporting, and marker updates consistent.

## Avoid Re-fetching Static SVG Icons
- `WaypointRenderer.updateTable` fetches icon SVGs on every table refresh, which is wasteful.
- Introduce an icon cache or reuse already-inserted `<span>` elements to shrink network chatter and improve responsiveness.
