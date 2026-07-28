# HPD Agent SDK vendor provenance

These private workspace packages are a source snapshot used to make the
HPDOS Penpot image build hermetic. They are not an independent fork and are
not published to npm.

| Package | Source | Source commit | Source-tree checksum |
| --- | --- | --- | --- |
| `@hpd-research/hpd-agent-client` | `typescript/hpd-agent-client/src` | `a41a46e4000f7015e4bd3eb9d4eb13eae0e2cd78` | `a085c5118f5b3397b753cc1edbd4434139c9540395f14960014bdbca32b7df61` |
| `@hpd-research/hpd-agent-client-tools-typescript` | `typescript/hpd-agent-client-tools-typescript/src` | `a41a46e4000f7015e4bd3eb9d4eb13eae0e2cd78` | `a898ac1758d410df342ef1273a4c1a26bbccaed07d16a0403c8872ba1282cee4` |

The source repository is the HPD-AI-Framework repository vendored by HPD-OS.
Each package carries the framework's `FSL-1.1-ALv2` license file.

Update both snapshots atomically whenever the Penpot harness adopts a newer
provider protocol. Recompute each checksum by hashing the sorted, path-relative
`src` file checksums. The workspace package versions are integration snapshot
identifiers; the source commit above is authoritative.
