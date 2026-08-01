#!/usr/bin/env node

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  chmodSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";

const root = mkdtempSync(join(tmpdir(), "penpot-hpdos-provenance-"));
try {
  const manifest = Buffer.from(
    JSON.stringify({
      schemaVersion: 2,
      config: { digest: `sha256:${"b".repeat(64)}`, size: 10 },
      layers: [
        { digest: `sha256:${"c".repeat(64)}`, size: 20 },
        { digest: `sha256:${"d".repeat(64)}`, size: 30 },
      ],
    }),
  );
  const manifestDigest = `sha256:${createHash("sha256")
    .update(manifest)
    .digest("hex")}`;
  const index = Buffer.from(
    JSON.stringify({
      schemaVersion: 2,
      manifests: [
        {
          digest: manifestDigest,
          platform: { os: "linux", architecture: "arm64" },
        },
      ],
    }),
  );
  const docker = join(root, "docker");
  writeFileSync(
    docker,
    `#!/bin/sh
set -eu
if [ "$1 $2 $3" = "buildx imagetools inspect" ]; then
  case "$5" in
    *@${manifestDigest}) printf '%s' '${manifest.toString("base64")}' | base64 -d ;;
    *) printf '%s' '${index.toString("base64")}' | base64 -d ;;
  esac
elif [ "$1 $2" = "image inspect" ]; then
  printf '%s' '[{"Os":"linux","Architecture":"arm64","Size":1000}]'
elif [ "$1" = "pull" ]; then
  exit 0
else
  echo "unexpected docker arguments: $*" >&2
  exit 2
fi
`,
  );
  chmodSync(docker, 0o700);

  const input = join(root, "images.env");
  const output = join(root, "provenance.json");
  writeFileSync(
    input,
    ["frontend", "backend", "exporter", "postgres", "valkey"]
      .map((name) => `${name}=registry.example/${name}:test`)
      .join("\n") + "\n",
  );
  execFileSync(
    process.execPath,
    ["docker/images/create-hpdos-image-provenance.mjs", input, output],
    {
      cwd: new URL("../..", import.meta.url),
      env: {
        ...process.env,
        DOCKER_CLI: docker,
        SOURCE_REPOSITORY: "https://github.com/example/penpot",
        BUILD_WORKFLOW: "https://github.com/example/penpot/actions/runs/1",
        BUNDLE_VERSION: "test-bundle",
      },
    },
  );

  const provenance = JSON.parse(readFileSync(output, "utf8"));
  assert.equal(provenance.schema, "hpdos.penpot-image-provenance/v3");
  assert.deepEqual(Object.keys(provenance.images), [
    "frontend",
    "backend",
    "exporter",
    "postgres",
    "valkey",
  ]);
  for (const image of Object.values(provenance.images)) {
    assert.match(image.reference, /^registry\.example\/.+@sha256:[0-9a-f]{64}$/);
    assert.equal(
      image.maximumDownloadBytes,
      index.length + manifest.length + 60,
    );
    assert.equal(image.maximumExpandedBytes, 1000);
  }
} finally {
  rmSync(root, { recursive: true, force: true });
}
