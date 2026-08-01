#!/usr/bin/env node

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";

const [inputPath, outputPath] = process.argv.slice(2);
if (!inputPath || !outputPath) {
  throw new Error(
    "usage: create-hpdos-image-provenance.mjs <images.env> <output.json>",
  );
}

const requiredEnvironment = [
  "SOURCE_REPOSITORY",
  "BUILD_WORKFLOW",
  "BUNDLE_VERSION",
];
for (const name of requiredEnvironment) {
  if (!process.env[name]?.trim()) {
    throw new Error(`${name} is required`);
  }
}

const imageNames = ["frontend", "backend", "exporter", "postgres", "valkey"];
const entries = readFileSync(inputPath, "utf8")
  .trim()
  .split("\n")
  .map((line) => {
    const separator = line.indexOf("=");
    if (separator <= 0) {
      throw new Error(`invalid image entry: ${line}`);
    }
    return [line.slice(0, separator), line.slice(separator + 1)];
  });
const mutableImages = Object.fromEntries(entries);
if (
  entries.length !== imageNames.length ||
  !imageNames.every((name) => Object.hasOwn(mutableImages, name))
) {
  throw new Error("image input must contain the exact production image set");
}

const docker = process.env.DOCKER_CLI?.trim() || "docker";
const dockerOptions = {
  encoding: "buffer",
  maxBuffer: 64 * 1024 * 1024,
};

function dockerOutput(args) {
  return execFileSync(docker, args, dockerOptions);
}

function parseJson(bytes, identity) {
  try {
    return JSON.parse(bytes.toString("utf8"));
  } catch {
    throw new Error(`${identity} returned malformed JSON`);
  }
}

function sha256(bytes) {
  return `sha256:${createHash("sha256").update(bytes).digest("hex")}`;
}

function repositoryOf(reference) {
  const digest = reference.indexOf("@");
  const withoutDigest = digest === -1 ? reference : reference.slice(0, digest);
  const slash = withoutDigest.lastIndexOf("/");
  const colon = withoutDigest.lastIndexOf(":");
  return colon > slash ? withoutDigest.slice(0, colon) : withoutDigest;
}

function positiveSafeInteger(value, identity) {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${identity} must be a positive safe integer`);
  }
  return value;
}

function addBound(total, value, identity) {
  const next = total + positiveSafeInteger(value, identity);
  if (!Number.isSafeInteger(next)) {
    throw new Error(`${identity} exceeds JavaScript's safe integer range`);
  }
  return next;
}

function resolveImage(name, mutableReference) {
  if (!/^[^@\s]+$/.test(mutableReference)) {
    throw new Error(`${name} must be a mutable OCI image reference`);
  }

  const repository = repositoryOf(mutableReference);
  const rootBytes = dockerOutput([
    "buildx",
    "imagetools",
    "inspect",
    "--raw",
    mutableReference,
  ]);
  const root = parseJson(rootBytes, `${name} image index`);
  const rootDigest = sha256(rootBytes);

  let manifest = root;
  let manifestBytes = rootBytes;
  if (Array.isArray(root.manifests)) {
    const candidates = root.manifests.filter(
      (candidate) =>
        candidate?.platform?.os === "linux" &&
        candidate?.platform?.architecture === "arm64",
    );
    if (candidates.length !== 1) {
      throw new Error(
        `${name} must publish exactly one linux/arm64 image manifest`,
      );
    }
    const platformDigest = candidates[0].digest;
    if (!/^sha256:[0-9a-f]{64}$/.test(platformDigest)) {
      throw new Error(`${name} linux/arm64 manifest digest is malformed`);
    }
    manifestBytes = dockerOutput([
      "buildx",
      "imagetools",
      "inspect",
      "--raw",
      `${repository}@${platformDigest}`,
    ]);
    manifest = parseJson(manifestBytes, `${name} linux/arm64 manifest`);
    if (sha256(manifestBytes) !== platformDigest) {
      throw new Error(`${name} linux/arm64 manifest digest changed`);
    }
  }

  if (!manifest.config || !Array.isArray(manifest.layers)) {
    throw new Error(`${name} linux/arm64 manifest is incomplete`);
  }
  let maximumDownloadBytes = rootBytes.length;
  if (manifestBytes !== rootBytes) {
    maximumDownloadBytes = addBound(
      maximumDownloadBytes,
      manifestBytes.length,
      `${name} manifest bytes`,
    );
  }
  maximumDownloadBytes = addBound(
    maximumDownloadBytes,
    manifest.config.size,
    `${name} config bytes`,
  );
  for (const [index, layer] of manifest.layers.entries()) {
    maximumDownloadBytes = addBound(
      maximumDownloadBytes,
      layer?.size,
      `${name} layer ${index} bytes`,
    );
  }

  const immutableReference = `${repository}@${rootDigest}`;
  execFileSync(
    docker,
    ["pull", "--platform", "linux/arm64", immutableReference],
    { stdio: "inherit" },
  );
  const inspected = parseJson(
    dockerOutput(["image", "inspect", immutableReference]),
    `${name} local image inspection`,
  );
  if (
    !Array.isArray(inspected) ||
    inspected.length !== 1 ||
    inspected[0]?.Os !== "linux" ||
    inspected[0]?.Architecture !== "arm64"
  ) {
    throw new Error(`${name} local image is not exactly linux/arm64`);
  }
  const maximumExpandedBytes = positiveSafeInteger(
    inspected[0].Size,
    `${name} expanded bytes`,
  );
  if (maximumExpandedBytes < maximumDownloadBytes) {
    throw new Error(
      `${name} expanded bytes are smaller than transferred bytes`,
    );
  }

  return {
    reference: immutableReference,
    maximumDownloadBytes,
    maximumExpandedBytes,
  };
}

const sourceCommit = execFileSync("git", ["rev-parse", "HEAD"], {
  encoding: "utf8",
}).trim();
if (!/^[0-9a-f]{40}$/.test(sourceCommit)) {
  throw new Error("source commit is not a full Git commit");
}

const images = Object.fromEntries(
  imageNames.map((name) => [name, resolveImage(name, mutableImages[name])]),
);
writeFileSync(
  outputPath,
  `${JSON.stringify(
    {
      schema: "hpdos.penpot-image-provenance/v3",
      sourceRepository: process.env.SOURCE_REPOSITORY,
      sourceCommit,
      buildWorkflow: process.env.BUILD_WORKFLOW,
      bundleVersion: process.env.BUNDLE_VERSION,
      images,
    },
    null,
    2,
  )}\n`,
);
