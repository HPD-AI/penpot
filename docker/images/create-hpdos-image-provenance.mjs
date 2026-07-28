#!/usr/bin/env node

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
const images = Object.fromEntries(entries);
for (const name of ["frontend", "backend", "exporter", "postgres", "valkey"]) {
  if (
    !images[name]?.match(
      /^[^@\s]+@sha256:[0-9a-f]{64}$/,
    )
  ) {
    throw new Error(`${name} must be an immutable OCI image reference`);
  }
}

const sourceCommit = execFileSync("git", ["rev-parse", "HEAD"], {
  encoding: "utf8",
}).trim();
if (!/^[0-9a-f]{40}$/.test(sourceCommit)) {
  throw new Error("source commit is not a full Git commit");
}

writeFileSync(
  outputPath,
  `${JSON.stringify(
    {
      schema: "hpdos.penpot-image-provenance/v1",
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
