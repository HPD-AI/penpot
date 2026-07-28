# HPD client tools upstream compatibility

This inventory defines the Penpot-internal surface consumed by the HPD native
adapter. Review every listed namespace and behavior when integrating a new
Penpot release, even when Git reports no conflict in `workspace/hpd.cljs`.

The HPD adapter is additive, but these internal APIs are not a stable public
extension contract.

This document governs Penpot-fork compatibility only. It does not define HPD-OS
runtime publication or client-tool authority. In production, Penpot services
currently run as HPD-OS App workloads inside an HPD Environment-managed Apple
VM, while the client-tool provider runs in the host browser/WebView
associated with a generation-bound web `BrowserLaunchSession`. Remote-display
`ViewerSession` remains a separate HPD-OS App Runtime resource. Native
Environment.Linux execution is a future transport and is not part of the
current Penpot delivery gate.

The authoritative companion specification is:

```text
HPD-Agent-InternalDocs/HPD-AI-Framework/hpd-client-tools/
PENPOT-HPDOS-RUNTIME-INTEGRATION-PLAN.md
```

It defines bootstrap authority, provider authentication, App and launch
identity, deterministic binding, lifecycle, recovery, URL/media authority, and
network boundaries.

## Patch inventory

| Area | HPD-owned file or Penpot integration point | Conflict risk | Removal condition |
| --- | --- | --- | --- |
| Provider contracts | `packages/hpd-client-tools/` | Low | Never; HPD-owned |
| Native dispatcher | `src/app/main/data/workspace/hpd.cljs` | Low textual, high semantic | Replace only with an upstream native automation API |
| Workspace lifecycle | `src/app/main/data/workspace.cljs` | Medium | Penpot gains a frontend extension lifecycle registry |
| Selective token propagation | `tokens/propagation.cljs` | Medium | Penpot exposes an equivalent bounded propagation API |
| Frontend configuration | `src/app/config.cljs` | Low | Ephemeral launch-session bootstrap replaces the development-only override |
| Container configuration | `docker/images/files/config.js`, `nginx-entrypoint.sh` | Low | Production launch bootstrap is injected by HPD-OS, never replaced by another static VM environment value |
| Backend runtime files | `backend/src/app/config.clj` | Low textual, security-sensitive | Upstream supports bounded file sources for the secret key, database password, and public URI |
| Exporter runtime files | `exporter/src/app/config.cljs` | Low textual, security-sensitive | Upstream supports bounded file sources for the secret key and public URI |
| Package workspace | `package.json`, `pnpm-workspace.yaml`, `pnpm-lock.yaml` | Medium | Never while the package remains in-tree |
| Native tests | `test/frontend_tests/data/workspace_hpd_test.cljs`, test runner | Low | Never; compatibility gate |
| HPDOS image workflow | `.github/workflows/build-hpdos-image-provenance.yml`, `docker/images/create-hpdos-image-provenance.mjs` | Low | Never; both files are HPD-owned and leave Penpot's workflow unchanged |

## Internal namespace inventory

### State, persistence, and lifecycle

| Namespace | Symbols and state relied upon | HPD behavior |
| --- | --- | --- |
| `app.main.store` | `state`, `emit!` | Live context, native event dispatch |
| `app.main.data.helpers` | `lookup-file`, `lookup-file-data`, `lookup-page`, `lookup-page-objects`, `lookup-shape`, `get-selected-ids`, `split-text-shapes` | Context and postcondition reads |
| `app.main.data.changes` | `commit-changes` | Atomic native mutations |
| `app.main.data.workspace.undo` | `start-undo-transaction`, `commit-undo-transaction` | Undo grouping and failure cleanup |
| `app.main.repo` | `cmd!` | Export and authenticated frontend RPC operations |
| `app.config` | `session-id`, `version`, `hpd-client-tools-uri` | Development connection bootstrap |

Review state keys used directly by the adapter:

```text
:current-file-id
:current-page-id
:permissions :can-edit
:workspace-global :read-only?
:workspace-local :selected
:workspace-undo :index
:workspace-undo :items
:files
file :revn
file :data
page :objects
```

### Document, selection, shapes, and transforms

| Namespace | Symbols |
| --- | --- |
| `workspace.pages` | `create-page`, `delete-page`, `duplicate-page`, `rename-page` |
| `workspace.selection` | `select-shapes`, `deselect-all`, `duplicate-shapes` |
| `workspace.shapes` | `add-shape`, `update-shapes`, `delete-shapes`, `relocate-shapes`, `create-artboard-from-shapes` |
| `workspace.transforms` | `position-shapes`, `update-position`, `update-dimensions`, `increase-rotation`, `flip-horizontal-selected`, `flip-vertical-selected`, `selected-fit-content`, `fit-layout-modifiers` |
| `common.files.changes-builder` | `empty-changes`, `with-objects`, `change-parent`, `move-page` |
| `common.files.helpers` | `get-children-with-self` |
| `common.types.shape` | `setup-shape` |
| `common.types.shape-tree` | `get-frame-id-by-position` |

### Hierarchy, vector, and layout

| Namespace | Symbols |
| --- | --- |
| `workspace.groups` | `group-shapes`, `ungroup-shapes`, `mask-group`, `unmask-group` |
| `workspace.bool` | `create-bool`, `change-bool-type`, `bool-to-group` |
| `workspace.path.shapes-to-path` | `convert-selected-to-path`, `convert-selected-strokes-to-path` |
| `workspace.shape-layout` | `create-layout-from-id`, `remove-layout`, `update-layout`, `update-layout-child`, `add-layout-track`, `change-layout-track`, `duplicate-layout-track`, `remove-layout-track`, `reorder-layout-track`, `create-cell-board`, `merge-cells`, `update-grid-cell-position`, `update-grid-cells` |
| `common.geom.align` | `align-to-parent`, `align-to-rect`, `distribute-space` |
| `common.geom.shapes` | `shapes->rect`, `fit-frame-modifiers` |
| `common.types.path` | `content`, `update-geometry` |

### Text and styles

| Namespace | Symbols |
| --- | --- |
| `workspace.texts` | `create-root-from-string`, `replace-text-in-shapes`, `update-all-attrs`, `update-text-range`, `update-text-with-function`, `apply-typography` |
| `common.types.text` | `content->text`, `generate-shape-name`, `node-seq`, `is-text-node?`, `is-paragraph-node?`, `replace-text-in-content`, `update-text-content` |
| `workspace.colors` | `add-fill`, `change-fill`, `remove-fill`, `add-stroke`, `change-stroke-color`, `remove-stroke`, `add-shadow` |
| `common.types.color` | `library-color->color` |
| `common.types.fills` | `create` |

### Components, variants, and assets

| Namespace | Symbols |
| --- | --- |
| `workspace.libraries` | `add-component`, `delete-component`, `duplicate-component`, `instantiate-component`, `detach-components`, `reset-components`, `restore-component`, `component-multi-swap`, `rename-component-and-main-instance`, `add-media` |
| `workspace.variants` | `add-new-variant`, `combine-as-variants`, `rename-variant`, `reorder-variant-poperties` |
| `workspace.media` | `upload-media-url`, `create-svg-shape-with-images` |
| `app.main.data.exports.assets` | Export command construction and returned resource shape |

### Tokens

| Namespace | Symbols |
| --- | --- |
| `workspace.tokens.application` | `apply-token`, `unapply-token`, `get-token-properties` |
| `workspace.tokens.library-edit` | `create-token`, `update-token`, `delete-token`, `import-tokens-lib` |
| `workspace.tokens.propagation` | `propagate-selected-workspace-tokens` |
| `workspace.tokens.remapping` | `remap-tokens` |
| `common.types.tokens-lib` | `ensure-tokens-lib`, `get-id`, `get-name`, `get-sets`, `get-set-by-name`, `get-tokens`, `get-themes`, `get-active-themes`, `make-token`, `parse-decoded-json`, `export-dtcg-json` |

### Prototype and collaboration

| Namespace | Symbols |
| --- | --- |
| `workspace.interactions` | `add-flow`, `update-flow`, `remove-flow`, `add-interaction`, `update-interaction`, `remove-interaction` |
| `common.types.shape.interactions` | `check-interaction` |
| `app.main.data.comments` | `retrieve-comment-threads`, `retrieve-comments`, `update-mentions` |

## Data-shape compatibility fixtures

The focused native suite must retain representative coverage for:

- shape and selection serialization;
- `appStateVersion` construction from file revision and undo state;
- read-only destructive-operation rejection;
- component-instance preconditions;
- token-library enumeration and bounded propagation;
- export RPC request and returned resource shape;
- undo transaction closure after a handler failure;
- semantic context subscription and disposal.

Add or update a focused fixture before accepting an upstream change to text,
layout/grid, variants, comments, or media data shapes.

## HPD Agent SDK snapshot

The two packages under `vendor/` are an HPDOS-owned, source-only snapshot of
the HPD Agent browser SDK. This avoids a host-checkout-relative dependency and
makes the production Penpot image reproducible in an isolated builder. The
snapshot is outside upstream Penpot ownership and therefore does not require
changes to upstream application packages.

`vendor/PROVENANCE.md` records the exact framework commit, license, and source
checksums. Update both packages together with the provider protocol and run the
full compatibility gate. Remove the snapshot only when an immutable private
package or build artifact with equivalent provenance is available; do not
replace it with another machine-relative link.

## Upgrade procedure

From `frontend/`, run:

```sh
pnpm run hpd:compat
```

The command:

1. type-checks and tests the HPD TypeScript package;
2. builds and runs the focused native HPD suite quietly;
3. checks formatting for all touched ClojureScript files;
4. compiles the main Penpot frontend;
5. reports full command output only when a step fails.

Also review the upstream diff for every namespace in this inventory. A green
compile cannot prove that a changed Penpot event still has the same semantic
postcondition.

For every accepted upstream version, record:

```text
previous upstream commit
new upstream commit or release tag
conflicted files
inventory entries affected
behavioral fixtures changed
HPD patches removed or replaced
commands executed
known deferred compatibility work
```
