# HPD Agent SDK vendor provenance

These private workspace packages are a source snapshot used to make the
HPDOS Penpot image build hermetic. They are not an independent fork and are
not published to npm.

| Package | Source | Source commit | Source-tree checksum |
| --- | --- | --- | --- |
| `@hpd-research/hpd-agent-client` | `typescript/hpd-agent-client/src` | `a4537ba8259c80bc39ec4fb4242724e5ce1aee06` | `f87ec6eb967cbd1a66ccd2c9e8c9f42cf1e00a1507a77693bffd104a20bba443` |
| `@hpd-research/hpd-agent-client-tools-typescript` | `typescript/hpd-agent-client-tools-typescript/src` | `a4537ba8259c80bc39ec4fb4242724e5ce1aee06` | `a898ac1758d410df342ef1273a4c1a26bbccaed07d16a0403c8872ba1282cee4` |

The source repository is the HPD-AI-Framework repository vendored by HPD-OS.
Each package carries the framework's `FSL-1.1-ALv2` license file.

Update both snapshots atomically whenever the Penpot harness adopts a newer
provider protocol. Recompute each checksum by hashing the sorted, path-relative
`src` file checksums. The workspace package versions are integration snapshot
identifiers; the source commit above is authoritative.
