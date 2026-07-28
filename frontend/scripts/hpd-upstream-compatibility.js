import { spawnSync } from "node:child_process";

const steps = [
  {
    label: "Type-checking HPD client tools",
    command: "pnpm",
    args: ["--filter", "@penpot/hpd-client-tools", "check"],
  },
  {
    label: "Testing HPD client-tool contracts",
    command: "pnpm",
    args: ["--filter", "@penpot/hpd-client-tools", "test"],
  },
  {
    label: "Building the native HPD adapter tests",
    command: "clojure",
    args: ["-M:dev:shadow-cljs", "compile", "test"],
  },
  {
    label: "Testing the native HPD adapter",
    command: "node",
    args: [
      "--conditions=hpd-native-test",
      "target/tests/test.js",
      "--focus",
      "frontend-tests.data.workspace-hpd-test",
      "--log-level",
      "warn",
    ],
  },
  {
    label: "Checking HPD ClojureScript formatting",
    command: "clojure",
    args: [
      "-M:dev",
      "-m",
      "cljfmt.main",
      "check",
      "src/app/main/data/workspace/hpd.cljs",
      "src/app/main/data/workspace/tokens/application.cljs",
      "src/app/main/data/workspace/tokens/propagation.cljs",
      "test/frontend_tests/data/workspace_hpd_test.cljs",
      "test/frontend_tests/runner.cljs",
    ],
  },
  {
    label: "Compiling the Penpot frontend",
    command: "clojure",
    args: ["-M:dev:shadow-cljs", "compile", "main"],
  },
];

const progress = (message) => process.stderr.write(`${message}\n`);
const environment = {
  ...process.env,
  SHADOW_SERVER_URL: process.env.SHADOW_SERVER_URL || "http://127.0.0.1:9630",
};

for (const step of steps) {
  progress(`${step.label}...`);
  const result = spawnSync(step.command, step.args, {
    env: environment,
    stdio: ["ignore", "pipe", "pipe"],
    maxBuffer: 128 * 1024 * 1024,
  });

  if (result.status !== 0) {
    progress(`${step.label} failed.`);
    if (result.stdout?.length) process.stdout.write(result.stdout);
    if (result.stderr?.length) process.stderr.write(result.stderr);
    process.exit(result.status ?? 1);
  }

  progress(`${step.label} passed.`);
}

progress("HPD upstream compatibility checks passed.");
