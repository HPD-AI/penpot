import { readFileSync } from "node:fs";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  actionPolicies,
  createPenpotClientToolsProvider,
  parametersSchema,
  parseOperationRequest,
  penpotToolDefinitions,
} from "./index";
import type { PenpotOperationRequest } from "./index";

const expectedTools = [
  "inspect",
  "document",
  "shapes",
  "transform",
  "hierarchy",
  "vector",
  "text",
  "layout",
  "styles",
  "components",
  "tokens",
  "prototype",
  "collaboration",
  "assets_output",
];

afterEach(() => {
  vi.unstubAllGlobals();
});

const dispatcherFunctions: Record<string, string> = {
  inspect: "inspect",
  document: "document-operation",
  shapes: "shapes-operation",
  transform: "transform-operation",
  hierarchy: "hierarchy-operation",
  vector: "vector-operation",
  text: "text-operation",
  layout: "layout-operation",
  styles: "styles-operation",
  components: "components-operation",
  tokens: "tokens-operation",
  prototype: "prototype-operation",
  collaboration: "collaboration-operation",
  assets_output: "assets-output-operation",
};

const expectedActionCounts: Record<string, number> = {
  inspect: 7,
  document: 6,
  shapes: 10,
  transform: 9,
  hierarchy: 9,
  vector: 7,
  text: 6,
  layout: 13,
  styles: 12,
  components: 15,
  tokens: 10,
  prototype: 7,
  collaboration: 6,
  assets_output: 6,
};

describe("Penpot compound-tool contracts", () => {
  it("registers the complete domain tool inventory", () => {
    expect(Object.keys(penpotToolDefinitions)).toEqual(expectedTools);
    expect(Object.fromEntries(
      Object.entries(penpotToolDefinitions).map(([name, definition]) => [
        name,
        Object.keys(definition.actions).length,
      ]),
    )).toEqual(expectedActionCounts);
    expect(Object.values(expectedActionCounts).reduce(
      (total, count) => total + count,
      0,
    )).toBe(123);
  });

  it("connects through HPD-OS launch bootstrap without a static URL", async () => {
    const socket = new FakeWebSocket();
    let openedUrl: string | undefined;
    let openedProtocols: string[] | undefined;
    const provider = createPenpotClientToolsProvider({
      resolveInitialLaunchBootstrap: async () => ({
        schema: "hpdos.client-tools-launch/v1",
        endpoint: {
          url: "wss://penpot.example/_hpd/client-tools",
          protocols: ["hpd-client-tools-v2"],
        },
        app: {
          appId: "penpot",
          appRevision: "revision-1",
          installationId: "installation-1",
          workloadId: "workload-1",
          workloadGeneration: 4,
          endpointId: "frontend",
          publicationId: "publication-1",
          publicationGeneration: 7,
          launchSurfaceId: "workspace",
          browserLaunchSessionId: "browser-launch-1",
          browserLaunchSessionGeneration: 2,
        },
        authority: {
          transport: "websocket-subprotocol",
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
          websocketProtocol: "opaque-one-time-authority",
        },
      }),
      instanceId: "frontend-runtime",
      adapter: {
        getContext: () => ({
          documentId: "file-1",
          pageId: "page-1",
          appStateVersion: "42",
        }),
        subscribeContextChanges: () => () => undefined,
        invoke: async () => undefined,
      },
      webSocketFactory: (url, protocols) => {
        openedUrl = url;
        openedProtocols = protocols;
        return socket as unknown as WebSocket;
      },
    });

    const connected = provider.connect();
    await waitUntil(() => openedUrl !== undefined);
    socket.open();
    socket.receive({
      type: "provider.welcome",
      clientRuntimeId: "runtime-1",
      connectionId: "connection-1",
      heartbeatIntervalMs: 60_000,
    });
    await connected;

    expect(openedUrl).toBe("wss://penpot.example/_hpd/client-tools");
    expect(openedProtocols).toEqual([
      "hpd-client-tools-v2",
      "opaque-one-time-authority",
    ]);
    expect(socket.sent.find((message) => message["type"] === "provider.manifest"))
      .toMatchObject({
        metadata: {
          connectionMode: "hpdos-browser-launch",
          hpdosRuntime: {
            installationId: "installation-1",
            browserLaunchSessionId: "browser-launch-1",
          },
        },
      });
    await provider.disconnect();
  });

  it("keeps schema, parser, and policy actions exactly aligned", () => {
    for (const definition of Object.values(penpotToolDefinitions)) {
      const schema = parametersSchema(definition);
      const branches = schema["oneOf"] as Array<{
        description: string;
        properties: { action: { const: string } };
        additionalProperties: boolean;
      }>;
      const schemaActions = branches.map(
        (branch) => branch.properties.action.const,
      );
      const policyActions = Object.keys(actionPolicies(definition));
      const definitionActions = Object.keys(definition.actions);

      expect(schemaActions).toEqual(definitionActions);
      expect(policyActions).toEqual(definitionActions);
      expect(branches.every((branch) => branch.additionalProperties === false))
        .toBe(true);
      expect(branches.map((branch) => branch.description)).toEqual(
        Object.values(definition.actions).map((action) => action.description),
      );
    }
  });

  it("declares every current background action as browser-owned", () => {
    const backgroundActions = Object.values(penpotToolDefinitions)
      .flatMap((definition) => Object.values(definition.actions))
      .filter((action) =>
        action.policy.invocationModePolicy === "BackgroundOnly"
      );

    expect(backgroundActions).toHaveLength(3);
    expect(backgroundActions.every((action) =>
      action.backgroundOwner === "browser-owned"
    )).toBe(true);
  });

  it("has a frontend dispatcher branch for every declared action", () => {
    const source = readFileSync(
      new URL("../../../src/app/main/data/workspace/hpd.cljs", import.meta.url),
      "utf8",
    );

    for (const [toolName, definition] of Object.entries(penpotToolDefinitions)) {
      const functionName = dispatcherFunctions[toolName];
      const start = source.indexOf(`(defn- ${functionName}`);
      const end = source.indexOf("\n(defn- ", start + 8);
      const dispatcher = source.slice(start, end < 0 ? undefined : end);

      expect(start, `${toolName} dispatcher`).toBeGreaterThanOrEqual(0);
      for (const actionName of Object.keys(definition.actions)) {
        expect(
          dispatcher.includes(`"${actionName}"`),
          `${toolName}.${actionName}`,
        ).toBe(true);
      }
    }
  });

  it("accepts background-only actions and publishes their terminal result", async () => {
    const socket = new FakeWebSocket();
    const provider = createPenpotClientToolsProvider({
      url: "ws://localhost/client-tools",
      instanceId: "penpot-test",
      webSocketFactory: () => socket as unknown as WebSocket,
      adapter: {
        getContext: () => ({
          documentId: "file-1",
          pageId: "page-1",
          appStateVersion: "42",
        }),
        subscribeContextChanges: () => () => undefined,
        invoke: async (_tool, request) => {
          if (request.action === "import") {
            throw new Error("Requested token set was not found.");
          }
          return {
            data: {
              action: "uploadMediaUrl",
              affectedIds: ["shape-1"],
            },
            context: {
              documentId: "file-1",
              appStateVersion: "41",
            },
          };
        },
      },
    });

    const connected = provider.connect();
    socket.open();
    socket.receive({
      type: "provider.welcome",
      clientRuntimeId: "runtime-1",
      connectionId: "connection-1",
      heartbeatIntervalMs: 60_000,
    });
    await connected;
    socket.clear();

    const policy = penpotToolDefinitions.assets_output
      .actions.uploadMediaUrl!.policy;
    socket.receive({
      type: "provider.invoke",
      protocolVersion: "2",
      clientRuntimeId: "runtime-1",
      connectionId: "connection-1",
      bindingId: "binding-1",
      invocationId: "invocation-1",
      requestId: "request-1",
      clientOperationId: "operation-1",
      toolName: "assets_output",
      visibleToolName: "penpot_design_assets_output",
      callId: "call-1",
      arguments: {
        action: "uploadMediaUrl",
        url: "https://example.test/image.png",
      },
      resolvedInvocationMode: "Background",
      operation: {
        discriminator: "action",
        action: "uploadMediaUrl",
        policy,
      },
      expectedContext: {
        documentId: "file-1",
        pageId: "page-1",
        appStateVersion: "42",
      },
    });

    await nextTick();
    await nextTick();

    expect(socket.sent.find((message) =>
      message["type"] === "provider.invokeOutcome",
    )).toMatchObject({
      outcome: "AcceptedBackground",
      handleKind: "ClientToolOperation",
    });
    expect(socket.sent.find((message) =>
      message["type"] === "provider.backgroundOperationOutcome",
    )).toMatchObject({
      state: "Completed",
      content: [{
        type: "json",
        value: {
          data: {
            action: "uploadMediaUrl",
            affectedIds: ["shape-1"],
          },
          context: {
            documentId: "file-1",
            pageId: "page-1",
            appStateVersion: "42",
          },
        },
      }],
    });

    socket.clear();
    socket.receive({
      type: "provider.invoke",
      protocolVersion: "2",
      clientRuntimeId: "runtime-1",
      connectionId: "connection-1",
      bindingId: "binding-1",
      invocationId: "invocation-2",
      requestId: "request-2",
      clientOperationId: "operation-2",
      toolName: "tokens",
      visibleToolName: "penpot_design_tokens",
      callId: "call-2",
      arguments: {
        action: "import",
        data: "{}",
        format: "json",
      },
      resolvedInvocationMode: "Background",
      operation: {
        discriminator: "action",
        action: "import",
        policy: penpotToolDefinitions.tokens.actions.import!.policy,
      },
      expectedContext: {
        documentId: "file-1",
        pageId: "page-1",
        appStateVersion: "42",
      },
    });

    await nextTick();
    await nextTick();

    expect(socket.sent.find((message) =>
      message["type"] === "provider.invokeOutcome",
    )).toMatchObject({ outcome: "AcceptedBackground" });
    expect(socket.sent.find((message) =>
      message["type"] === "provider.backgroundOperationOutcome",
    )).toMatchObject({
      state: "Faulted",
      error: {
        kind: "resource_not_found",
        retryable: false,
      },
    });

    await provider.disconnect();
  });

  it("publishes completed exports as bounded HPD-OS binary resources", async () => {
    vi.stubGlobal("location", {
      origin: "https://penpot.example",
    });
    vi.stubGlobal("sessionStorage", {
      getItem: () => "bls_0123456789abcdef0123456789abcdef",
      setItem: () => undefined,
    });
    const socket = new FakeWebSocket();
    const provider = createPenpotClientToolsProvider({
      launchBootstrap: {
        schema: "hpdos.client-tools-launch/v1",
        endpoint: {
          url: "wss://penpot.example/_hpd/client-tools",
          protocols: ["hpd-client-tools-v2"],
        },
        app: {
          appId: "io.penpot.penpot",
          appRevision: "revision-1",
          installationId: "installation-1",
          workloadId: "frontend",
          workloadGeneration: 4,
          endpointId: "web",
          publicationId: "publication-1",
          publicationGeneration: 7,
          launchSurfaceId: "workspace",
          browserLaunchSessionId:
            "bls_0123456789abcdef0123456789abcdef",
          browserLaunchSessionGeneration: 2,
        },
        authority: {
          transport: "websocket-subprotocol",
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
          websocketProtocol: "opaque-export-authority",
        },
      },
      instanceId: "penpot-export-test",
      webSocketFactory: () => socket as unknown as WebSocket,
      publishExportResource: async (resource) => {
        expect(resource).toEqual({
          path: "/api/export/resource-1",
          filename: "selection.zip",
          mediaType: "application/zip",
        });
        return {
          id: "hpr_resource",
          url: "https://penpot.example/_hpd/resources/content/hpr_resource",
          mimeType: "application/zip",
          filename: "selection.zip",
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
        };
      },
      adapter: {
        getContext: () => ({
          documentId: "file-1",
          pageId: "page-1",
          appStateVersion: "42",
        }),
        subscribeContextChanges: () => () => undefined,
        invoke: async () => ({
          data: {
            action: "exportShapes",
            affectedIds: ["shape-1"],
            resourceId: "guest-private-resource",
            resource: {
              path: "/api/export/resource-1",
              filename: "selection.zip",
              mediaType: "application/zip",
            },
          },
          context: {
            documentId: "file-1",
            pageId: "page-1",
            appStateVersion: "42",
          },
        }),
      },
    });

    const connected = provider.connect();
    await waitUntil(() => socket.onopen !== null);
    socket.open();
    socket.receive({
      type: "provider.welcome",
      clientRuntimeId: "runtime-export",
      connectionId: "connection-export",
      heartbeatIntervalMs: 60_000,
    });
    await connected;
    socket.clear();

    socket.receive({
      type: "provider.invoke",
      protocolVersion: "2",
      clientRuntimeId: "runtime-export",
      connectionId: "connection-export",
      bindingId: "binding-export",
      invocationId: "invocation-export",
      requestId: "request-export",
      clientOperationId: "operation-export",
      toolName: "assets_output",
      visibleToolName: "penpot_design_assets_output",
      callId: "call-export",
      arguments: {
        action: "exportShapes",
        shapeIds: ["shape-1"],
        exports: [{ type: "png", scale: 1 }],
      },
      resolvedInvocationMode: "Background",
      operation: {
        discriminator: "action",
        action: "exportShapes",
        policy:
          penpotToolDefinitions.assets_output.actions.exportShapes!.policy,
      },
      expectedContext: {
        documentId: "file-1",
        pageId: "page-1",
        appStateVersion: "42",
      },
    });

    await nextTick();
    await nextTick();
    await nextTick();

    const completed = socket.sent.find((message) =>
      message["type"] === "provider.backgroundOperationOutcome"
    );
    expect(completed).toMatchObject({
      state: "Completed",
      content: [
        {
          type: "json",
          value: {
            data: {
              action: "exportShapes",
              resource: {
                id: "hpr_resource",
              },
            },
          },
        },
        {
          type: "binary",
          id: "hpr_resource",
          mimeType: "application/zip",
          filename: "selection.zip",
        },
      ],
    });
    expect(JSON.stringify(completed)).not.toContain(
      "guest-private-resource",
    );
    await provider.disconnect();
  });

  it("republishes the Penpot manifest when its native context changes", async () => {
    const socket = new FakeWebSocket();
    let context = {
      documentId: "file-1",
      pageId: "page-1",
      appStateVersion: "42:0:1",
    };
    let notifyContextChanged: (() => void) | undefined;
    const provider = createPenpotClientToolsProvider({
      url: "ws://localhost/client-tools",
      instanceId: "penpot-test",
      webSocketFactory: () => socket as unknown as WebSocket,
      contextUpdateDebounceMs: 0,
      adapter: {
        getContext: () => context,
        subscribeContextChanges: (listener) => {
          notifyContextChanged = listener;
          return () => undefined;
        },
        invoke: () => ({ data: null, context }),
      },
    });

    const connected = provider.connect();
    socket.open();
    socket.receive({
      type: "provider.welcome",
      clientRuntimeId: "runtime-1",
      connectionId: "connection-1",
      heartbeatIntervalMs: 60_000,
    });
    await connected;
    await nextTick();
    socket.clear();

    context = {
      documentId: "file-1",
      pageId: "page-2",
      appStateVersion: "42:1:2",
    };
    notifyContextChanged?.();
    notifyContextChanged?.();
    await nextTick();

    expect(socket.sent.filter((message) =>
      message["type"] === "provider.manifest"
    )).toHaveLength(1);
    expect(socket.sent.find((message) =>
      message["type"] === "provider.manifest"
    )).toMatchObject({ context });

    await provider.disconnect();
  });

  it("parses a bounded inspection request", () => {
    expect(parseOperationRequest(penpotToolDefinitions.inspect, {
      action: "getPageTree",
      pageId: "page-1",
      depth: 4,
    })).toEqual({
      action: "getPageTree",
      pageId: "page-1",
      depth: 4,
    });
  });

  it("models each domain request as an action-specific discriminated union", () => {
    const request = {
      action: "move",
      shapeIds: ["shape-1"],
      delta: { x: 10, y: -5 },
    } satisfies PenpotOperationRequest<"transform">;

    expect(request.action).toBe("move");

    // @ts-expect-error move requires delta
    const missingDelta: PenpotOperationRequest<"transform"> = {
      action: "move",
      shapeIds: ["shape-1"],
    };
    expect(missingDelta).toBeDefined();

    const wrongDomain = {
      // @ts-expect-error createPage belongs to the document domain
      action: "createPage",
      name: "Page 2",
    } satisfies PenpotOperationRequest<"transform">;
    expect(wrongDomain).toBeDefined();
  });

  it("parses a closed nested mutation request", () => {
    expect(parseOperationRequest(penpotToolDefinitions.transform, {
      action: "move",
      shapeIds: ["shape-1", "shape-2"],
      delta: { x: 10, y: -5 },
    })).toEqual({
      action: "move",
      shapeIds: ["shape-1", "shape-2"],
      delta: { x: 10, y: -5 },
    });
  });

  it("rejects unknown top-level fields", () => {
    expect(() => parseOperationRequest(penpotToolDefinitions.inspect, {
      action: "getContext",
      executeCode: "anything",
    })).toThrow("Unexpected operation request field 'executeCode'.");
  });

  it("rejects unknown nested patch fields", () => {
    expect(() => parseOperationRequest(penpotToolDefinitions.shapes, {
      action: "patch",
      shapeIds: ["shape-1"],
      patch: { arbitraryPropertyPath: "content.raw" },
    })).toThrow("patch.arbitraryPropertyPath is not supported.");
  });

  it("rejects missing required fields", () => {
    expect(() => parseOperationRequest(penpotToolDefinitions.document, {
      action: "renamePage",
      pageId: "page-1",
    })).toThrow("Required operation request field 'name' is missing.");
  });

  it("rejects invalid numeric constraints", () => {
    expect(() => parseOperationRequest(penpotToolDefinitions.transform, {
      action: "resize",
      shapeIds: ["shape-1"],
      width: 0,
      height: 100,
    })).toThrow("width must be greater than its minimum.");
  });

  it("requires exactly one valid color source", () => {
    expect(() => parseOperationRequest(penpotToolDefinitions.styles, {
      action: "addFill",
      shapeIds: ["shape-1"],
      fill: {
        color: "#ffffff",
        imageId: "media-1",
      },
    })).toThrow("fill must match exactly one supported shape.");
  });

  it("validates complete gradient geometry and stops", () => {
    expect(parseOperationRequest(penpotToolDefinitions.styles, {
      action: "addFill",
      shapeIds: ["shape-1"],
      fill: {
        gradient: {
          type: "linear",
          startX: 0,
          startY: 0,
          endX: 1,
          endY: 0,
          width: 1,
          stops: [
            { color: "#000000", offset: 0 },
            { color: "#ffffff", opacity: 0.5, offset: 1 },
          ],
        },
      },
    })).toMatchObject({ action: "addFill" });
  });

  it("rejects empty semantic patches", () => {
    expect(() => parseOperationRequest(penpotToolDefinitions.shapes, {
      action: "patch",
      shapeIds: ["shape-1"],
      patch: {},
    })).toThrow("patch must contain at least one property.");
  });

  it("validates grid track values by track type", () => {
    expect(parseOperationRequest(penpotToolDefinitions.layout, {
      action: "addTrack",
      shapeId: "grid-1",
      trackType: "column",
      value: { type: "auto" },
    })).toMatchObject({ action: "addTrack" });

    expect(() => parseOperationRequest(penpotToolDefinitions.layout, {
      action: "addTrack",
      shapeId: "grid-1",
      trackType: "column",
      value: { type: "fixed" },
    })).toThrow("value must match exactly one supported shape.");
  });

  it("marks every file mutation as permissioned and fresh", () => {
    for (const [toolName, definition] of Object.entries(penpotToolDefinitions)) {
      for (const [actionName, action] of Object.entries(definition.actions)) {
        if (action.policy.mutatesState === true && action.policy.requiresPermission === true) {
          expect(
            action.policy.requiresFreshContext,
            `${toolName}.${actionName}`,
          ).toBe(true);
          expect(
            action.policy.permissionScope,
            `${toolName}.${actionName}`,
          ).toMatch(/^penpot\./);
        }
      }
    }
  });
});

function nextTick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

async function waitUntil(condition: () => boolean): Promise<void> {
  const deadline = Date.now() + 1_000;
  while (!condition()) {
    if (Date.now() >= deadline) {
      throw new Error("Condition was not met before the test timeout.");
    }
    await nextTick();
  }
}

class FakeWebSocket {
  public readyState = 0;
  public sent: Array<Record<string, unknown>> = [];
  public onopen: (() => void) | null = null;
  public onclose: (() => void) | null = null;
  public onerror: (() => void) | null = null;
  public onmessage:
    | ((event: { data: string }) => void | Promise<void>)
    | null = null;

  public open(): void {
    this.readyState = 1;
    this.onopen?.();
  }

  public close(): void {
    this.readyState = 3;
    this.onclose?.();
  }

  public send(text: string): void {
    this.sent.push(JSON.parse(text) as Record<string, unknown>);
  }

  public receive(message: Record<string, unknown>): void {
    void this.onmessage?.({ data: JSON.stringify(message) });
  }

  public clear(): void {
    this.sent = [];
  }
}
