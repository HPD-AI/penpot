# Penpot HPD client tools

This package exposes the active Penpot workspace as the `penpot_design`
client-tool harness. Penpot owns execution: each handler calls the same
ClojureScript events and frontend services used by the editor.

## Enable the provider

Production HPD-OS launches use the host-owned attachment route and
same-origin bootstrap. The attachment route records a non-authoritative
`BrowserLaunchSession` ID in tab-scoped session storage; the signed cookie
remains `HttpOnly`. Penpot then resolves one-time WebSocket authority from the
reserved `/_hpd/client-tools/bootstrap/{sessionId}` route.

Standalone development can set the browser global
`penpotHpdClientToolsURI`. Container development must opt in explicitly:

```text
PENPOT_HPD_CLIENT_TOOLS_DEVELOPMENT=1
PENPOT_HPD_CLIENT_TOOLS_URI=wss://example.test/api/hpd/client-tool-providers/connect
```

The static URI is ignored unless the development switch is exactly `1`. In
production, no provider is created without the HPD-OS attachment marker. The
provider connects after a workspace opens and disconnects when that workspace
is finalized.

The HPD client dependency is linked directly while the integration is under
development:

```text
@hpd-research/hpd-agent-client-tools-typescript
```

Replace the link dependency with the published package when it becomes
available; the Penpot adapter and contracts do not depend on its source tree.

Upstream Penpot compatibility ownership, internal API dependencies, and the
upgrade gate are documented in `UPSTREAM-COMPATIBILITY.md`.

## HPDOS production images

The Docker image workflow publishes a machine-readable
`penpot-hpdos-image-provenance-<run-id>` artifact after pushing the customized
frontend, backend, and exporter images. It records:

- the exact checked-out fork commit and bundle version;
- the workflow-run identity;
- immutable multi-architecture manifest digests for the three Penpot images;
- immutable PostgreSQL and Valkey dependency digests.
- Linux/arm64 maximum transferred and expanded byte counts measured from the
  immutable manifests and pulled images.

The `.hpdapp` packaging job must consume that artifact. Mutable tags are build
inputs only and must never appear in an accepted App manifest or production
Compose file.

## Harness surface

The harness contains domain tools rather than one monolithic operation:

```text
inspect         document       shapes          transform
hierarchy       vector         text            layout
styles          components     tokens          prototype
collaboration   assets_output
```

Each tool is a closed compound tool whose `action` discriminator selects one
of its operations. The contracts, action policies, permissions, and request
validation live in `src/contracts.ts`. The native frontend dispatcher lives in
`../../src/app/main/data/workspace/hpd.cljs`.

Successful operations return one JSON content item:

```json
{
  "data": {
    "action": "move",
    "affectedIds": ["shape-id"]
  },
  "context": {
    "documentId": "file-id",
    "pageId": "page-id",
    "appStateVersion": "revision",
    "metadata": {
      "selectionIds": [],
      "canEdit": true,
      "readOnly": false
    }
  }
}
```

Writes are permission-scoped and require fresh context. Long-running imports,
exports, and URL uploads use HPD background outcomes. These actions explicitly
declare `browser-owned` execution: HPD assigns the stable operation ID, loss of
the owning provider yields a non-success/unknown outcome, and the operation is
never replayed. A future durable operation must instead introduce a native
Penpot server job with explicit status and cancellation actions.

The native adapter subscribes to semantic workspace-context changes. The HPD
provider SDK debounces and deduplicates those notifications before publishing
a new manifest, so mutations, undo/redo, selection, active-page, and
permission changes cannot leave HPD with an indefinitely stale context.

## Verification

From `frontend/`:

```sh
pnpm run hpd:compat
```

The TypeScript contract tests verify the wire lifecycle and that every
declared action has a native ClojureScript dispatcher branch. Focused native
tests execute representative inspection, mutation safety, component, token
propagation, and background-export paths:

```sh
node --conditions=hpd-native-test target/tests/test.js \
  --focus frontend-tests.data.workspace-hpd-test \
  --log-level warn
```
