import {
  createClientToolProvider,
  type ClientToolProvider,
  type ClientToolProviderEndpointResolutionReason,
  type ClientToolProviderHarnessBuilder,
  type ClientToolProviderToolResult,
  type ProviderConnectionStateChange,
  type ProviderContextSnapshot,
} from "@hpd-research/hpd-agent-client-tools-typescript";
import {
  actionPolicies,
  parametersSchema,
  parseOperationRequest,
  penpotToolDefinitions,
  type PenpotOperationRequest,
  type PenpotToolDefinition,
  type PenpotToolName,
} from "./contracts.ts";

export {
  actionPolicies,
  parametersSchema,
  parseOperationRequest,
  penpotToolDefinitions,
} from "./contracts.ts";
export type {
  PenpotOperationRequest,
  PenpotToolName,
  PenpotToolDefinition,
} from "./contracts.ts";

export interface PenpotFrontendAdapter {
  getContext(): ProviderContextSnapshot | Promise<ProviderContextSnapshot>;
  subscribeContextChanges(onContextChanged: () => void): () => void;
  invoke<TTool extends PenpotToolName>(
    tool: TTool,
    request: PenpotOperationRequest<TTool>,
  ): unknown | Promise<unknown>;
}

export interface PenpotClientToolsOptions {
  url?: string;
  launchBootstrap?: HpdosClientToolLaunchBootstrap;
  resolveInitialLaunchBootstrap?: () => Promise<HpdosClientToolLaunchBootstrap>;
  refreshLaunchBootstrap?: (
    reason: ClientToolProviderEndpointResolutionReason,
  ) => Promise<HpdosClientToolLaunchBootstrap>;
  instanceId: string;
  version?: string;
  adapter: PenpotFrontendAdapter;
  webSocketFactory?: (url: string, protocols?: string[]) => WebSocket;
  contextUpdateDebounceMs?: number;
  onConnectionStateChange?: (change: ProviderConnectionStateChange) => void;
  onBackgroundOperationAbandoned?: (operation: {
    clientOperationId: string;
    bindingId: string;
    reason: "provider_disconnected" | "provider_revoked" | "provider_closed";
  }) => void;
  authorizeUrlImport?: (url: string) => Promise<string>;
  publishExportResource?: (resource: {
    path: string;
    filename: string;
    mediaType: string;
  }) => Promise<HpdosPublishedClientToolResource>;
}

export interface HpdosPublishedClientToolResource {
  id: string;
  url: string;
  mimeType: string;
  filename: string;
  expiresAt: string;
}

export interface HpdosClientToolLaunchBootstrap {
  schema: "hpdos.client-tools-launch/v1";
  endpoint: {
    url: string;
    protocols?: string[];
  };
  app: {
    appId: string;
    appRevision: string;
    installationId: string;
    workloadId: string;
    workloadGeneration: number;
    endpointId: string;
    publicationId: string;
    publicationGeneration: number;
    launchSurfaceId: string;
    browserLaunchSessionId: string;
    browserLaunchSessionGeneration: number;
  };
  authority: {
    transport: "cookie" | "websocket-subprotocol";
    expiresAt: string;
    refreshAfter?: string;
    websocketProtocol?: string;
  };
}

export function createPenpotClientToolsProvider(
  options: PenpotClientToolsOptions,
): ClientToolProvider {
  const version = options.version ?? "0.1.0";
  if (options.launchBootstrap === undefined &&
      options.resolveInitialLaunchBootstrap === undefined &&
      options.url === undefined) {
    throw new Error(
      "Penpot client tools require HPD-OS launch bootstrap or an explicit development URL.",
    );
  }

  let initialBootstrap = options.launchBootstrap;
  let runtimeIdentity = initialBootstrap?.app;
  const connection = initialBootstrap === undefined &&
      options.resolveInitialLaunchBootstrap === undefined
    ? undefined
    : {
        resolveEndpoint: async (
          reason: ClientToolProviderEndpointResolutionReason,
        ) => {
          const bootstrap = reason === "initial"
            ? initialBootstrap ??
              await options.resolveInitialLaunchBootstrap!()
            : await (
                options.refreshLaunchBootstrap !== undefined
                  ? options.refreshLaunchBootstrap(reason)
                  : refreshLaunchBootstrapFromSameOrigin(
                      runtimeIdentity?.browserLaunchSessionId,
                      reason,
                    )
              );
          initialBootstrap = undefined;
          validateLaunchBootstrap(bootstrap);
          runtimeIdentity = bootstrap.app;
          rememberBrowserLaunchSession(
            bootstrap.app.browserLaunchSessionId,
          );
          return {
            url: bootstrap.endpoint.url,
            protocols: connectionProtocols(bootstrap),
            expiresAt: bootstrap.authority.expiresAt,
          };
        },
      };
  const provider = createClientToolProvider({
    url: options.url,
    connection,
    identity: {
      providerName: "penpot-frontend",
      appKind: "design-editor",
      instanceId: options.instanceId,
      version,
    },
    appProvider: {
      name: "penpot",
      displayName: "Penpot",
      description: "Native tools for the active Penpot workspace.",
      version,
    },
    context: () => options.adapter.getContext(),
    contextSnapshot: () => options.adapter.getContext(),
    subscribeContextChanges: (listener) =>
      options.adapter.subscribeContextChanges(listener),
    contextUpdateDebounceMs: options.contextUpdateDebounceMs,
    readiness: "Ready",
    webSocketFactory: options.webSocketFactory,
    metadata: () => runtimeIdentity === undefined
      ? { connectionMode: "standalone-development" }
      : {
          connectionMode: "hpdos-browser-launch",
          hpdosRuntime: runtimeIdentity,
        },
    onConnectionStateChange: options.onConnectionStateChange,
    onBackgroundOperationAbandoned: options.onBackgroundOperationAbandoned,
  });

  const harness = provider.harness("penpot_design", {
    description: "Inspect and edit the active Penpot design.",
    startCollapsed: true,
    systemPrompt:
      "Inspect current Penpot state before editing. Preserve file and page context, honor revisions, and use semantic domain actions.",
  });

  registerPenpotTool<PenpotOperationRequest<"inspect">>(
    provider, harness, "inspect", penpotToolDefinitions.inspect,
    (request) => invokeAdapter(options, "inspect", request),
  );
  registerPenpotTool<PenpotOperationRequest<"document">>(
    provider, harness, "document", penpotToolDefinitions.document,
    (request) => invokeAdapter(options, "document", request),
  );
  registerPenpotTool<PenpotOperationRequest<"shapes">>(
    provider, harness, "shapes", penpotToolDefinitions.shapes,
    (request) => invokeAdapter(options, "shapes", request),
  );
  registerPenpotTool<PenpotOperationRequest<"transform">>(
    provider, harness, "transform", penpotToolDefinitions.transform,
    (request) => invokeAdapter(options, "transform", request),
  );
  registerPenpotTool<PenpotOperationRequest<"hierarchy">>(
    provider, harness, "hierarchy", penpotToolDefinitions.hierarchy,
    (request) => invokeAdapter(options, "hierarchy", request),
  );
  registerPenpotTool<PenpotOperationRequest<"vector">>(
    provider, harness, "vector", penpotToolDefinitions.vector,
    (request) => invokeAdapter(options, "vector", request),
  );
  registerPenpotTool<PenpotOperationRequest<"text">>(
    provider, harness, "text", penpotToolDefinitions.text,
    (request) => invokeAdapter(options, "text", request),
  );
  registerPenpotTool<PenpotOperationRequest<"layout">>(
    provider, harness, "layout", penpotToolDefinitions.layout,
    (request) => invokeAdapter(options, "layout", request),
  );
  registerPenpotTool<PenpotOperationRequest<"styles">>(
    provider, harness, "styles", penpotToolDefinitions.styles,
    (request) => invokeAdapter(options, "styles", request),
  );
  registerPenpotTool<PenpotOperationRequest<"components">>(
    provider, harness, "components", penpotToolDefinitions.components,
    (request) => invokeAdapter(options, "components", request),
  );
  registerPenpotTool<PenpotOperationRequest<"tokens">>(
    provider, harness, "tokens", penpotToolDefinitions.tokens,
    (request) => invokeAdapter(options, "tokens", request),
  );
  registerPenpotTool<PenpotOperationRequest<"prototype">>(
    provider, harness, "prototype", penpotToolDefinitions.prototype,
    (request) => invokeAdapter(options, "prototype", request),
  );
  registerPenpotTool<PenpotOperationRequest<"collaboration">>(
    provider,
    harness,
    "collaboration",
    penpotToolDefinitions.collaboration,
    (request) => invokeAdapter(options, "collaboration", request),
  );
  registerPenpotTool<PenpotOperationRequest<"assets_output">>(
    provider,
    harness,
    "assets_output",
    penpotToolDefinitions.assets_output,
    (request) => invokeAdapter(options, "assets_output", request),
  );

  return provider;
}

async function refreshLaunchBootstrapFromSameOrigin(
  browserLaunchSessionId: string | undefined,
  _reason: ClientToolProviderEndpointResolutionReason,
): Promise<HpdosClientToolLaunchBootstrap> {
  if (browserLaunchSessionId === undefined ||
      !/^bls_[a-zA-Z0-9_-]+$/.test(browserLaunchSessionId)) {
    throw new Error(
      "HPD-OS browser launch session identity is unavailable for bootstrap refresh.",
    );
  }
  const response = await fetch(
    `/_hpd/client-tools/bootstrap/${browserLaunchSessionId}/refresh`,
    {
      method: "POST",
      credentials: "include",
      cache: "no-store",
      redirect: "error",
      headers: {
        accept: "application/json",
      },
    },
  );
  if (!response.ok) {
    throw new Error(
      `HPD-OS client-tool bootstrap refresh failed with status ${response.status}.`,
    );
  }

  return await response.json() as HpdosClientToolLaunchBootstrap;
}

function validateLaunchBootstrap(
  bootstrap: HpdosClientToolLaunchBootstrap,
): void {
  if (bootstrap.schema !== "hpdos.client-tools-launch/v1") {
    throw new Error(
      `Unsupported HPD-OS client-tool launch bootstrap '${String(bootstrap.schema)}'.`,
    );
  }
  if (bootstrap.endpoint.url.trim().length === 0) {
    throw new Error("HPD-OS client-tool launch bootstrap endpoint is empty.");
  }
  if (Date.parse(bootstrap.authority.expiresAt) <= Date.now()) {
    throw new Error("HPD-OS client-tool launch bootstrap authority is expired.");
  }
  if (bootstrap.authority.transport === "websocket-subprotocol" &&
      (bootstrap.authority.websocketProtocol?.trim().length ?? 0) === 0) {
    throw new Error(
      "HPD-OS WebSocket-subprotocol authority did not include its opaque protocol.",
    );
  }
}

function rememberBrowserLaunchSession(
  browserLaunchSessionId: string,
): void {
  if (typeof sessionStorage === "undefined") {
    return;
  }
  sessionStorage.setItem(
    "hpdos.browserLaunchSessionId",
    browserLaunchSessionId,
  );
}

function connectionProtocols(
  bootstrap: HpdosClientToolLaunchBootstrap,
): string[] | undefined {
  const protocols = [...(bootstrap.endpoint.protocols ?? [])];
  const authorityProtocol = bootstrap.authority.websocketProtocol;
  if (bootstrap.authority.transport === "websocket-subprotocol" &&
      authorityProtocol !== undefined &&
      !protocols.includes(authorityProtocol)) {
    protocols.push(authorityProtocol);
  }
  return protocols.length === 0 ? undefined : protocols;
}

function registerPenpotTool<TRequest extends { action: string }>(
  provider: ClientToolProvider,
  harness: ClientToolProviderHarnessBuilder,
  toolName: PenpotToolName,
  definition: PenpotToolDefinition,
  invoke: (request: TRequest) => Promise<unknown>,
): void {
  harness.operationTool<TRequest, "action">(toolName, {
    description: definition.description,
    discriminator: "action",
    parametersSchema: parametersSchema(definition),
    actions: actionPolicies(definition),
    parse: (value) => parseOperationRequest(definition, value) as TRequest,
    handler: async (request, context): Promise<ClientToolProviderToolResult> => {
      if (context.resolvedInvocationMode === "Background") {
        const clientOperationId = context.invocation.clientOperationId;
        if (clientOperationId === undefined) {
          throw new Error(
            "HPD did not assign an operation id for background work.",
          );
        }
        context.acceptBackground({
          handleKind: "ClientToolOperation",
        });
        void Promise.resolve()
          .then(() => invoke(request))
          .then((value) => {
            provider.completeBackgroundOperation(
              clientOperationId,
              toolResult(value),
            );
          })
          .catch((error: unknown) => {
            provider.failBackgroundOperation(
              clientOperationId,
              clientToolError(error),
            );
          });
        return;
      }

      try {
        return toolResult(await invoke(request));
      } catch (error: unknown) {
        context.fail(clientToolError(error));
        return;
      }
    },
  });
}

async function invokeAdapter<TTool extends PenpotToolName>(
  options: PenpotClientToolsOptions,
  toolName: TTool,
  request: PenpotOperationRequest<TTool>,
): Promise<unknown> {
  let effectiveRequest = request;
  const assetsRequest = request as unknown as {
    action?: unknown;
    url?: unknown;
  };
  if (toolName === "assets_output" &&
      assetsRequest.action === "uploadMediaUrl" &&
      options.url === undefined) {
    if (options.authorizeUrlImport === undefined) {
      throw new Error(
        "HPD-OS URL-import authority is required for production URL uploads.",
      );
    }
    if (typeof assetsRequest.url !== "string") {
      throw new Error("URL upload request is missing its URL.");
    }
    const authorizedUrl =
      await options.authorizeUrlImport(assetsRequest.url);
    requireHpdosResourceUrl(authorizedUrl);
    effectiveRequest = {
      ...request,
      url: authorizedUrl,
    } as PenpotOperationRequest<TTool>;
  }
  let result = await options.adapter.invoke(toolName, effectiveRequest);
  if (toolName === "assets_output" &&
      assetsRequest.action === "exportShapes" &&
      options.url === undefined) {
    const resource = exportResource(result);
    const published = await (
      options.publishExportResource ??
        publishExportResourceToSameOrigin
    )(resource);
    requireHpdosResourceUrl(published.url);
    const {
      resourceId: _guestResourceId,
      ...safeData
    } = (result as { data: Record<string, unknown> }).data;
    result = {
      ...result as Record<string, unknown>,
      data: {
        ...safeData,
        resource: published,
      },
    };
  }
  if (isRecord(result) && "context" in result) {
    return {
      ...result,
      context: await options.adapter.getContext(),
    };
  }
  return result;
}

function requireHpdosResourceUrl(value: string): void {
  const base = typeof location === "undefined"
    ? undefined
    : location.origin;
  const parsed = new URL(value, base);
  if (base === undefined ||
      parsed.origin !== base ||
      !parsed.pathname.startsWith("/_hpd/resources/") ||
      parsed.username.length > 0 ||
      parsed.password.length > 0) {
    throw new Error(
      "HPD-OS URL-import authority returned an invalid resource capability.",
    );
  }
}

function toolResult(value: unknown): ClientToolProviderToolResult {
  const content: Array<
    | { type: "json"; value: unknown }
    | {
      type: "binary";
      id: string;
      url: string;
      mimeType: string;
      filename: string;
    }
  > = [
    {
      type: "json",
      value,
    },
  ];
  const published = publishedExportResource(value);
  if (published !== undefined) {
    content.push({
      type: "binary",
      id: published.id,
      url: published.url,
      mimeType: published.mimeType,
      filename: published.filename,
    });
  }
  return content;
}

function exportResource(value: unknown): {
  path: string;
  filename: string;
  mediaType: string;
} {
  if (!isRecord(value) ||
      !isRecord(value["data"]) ||
      !isRecord(value["data"]["resource"])) {
    throw new Error(
      "Penpot export completed without resource metadata.",
    );
  }
  const resource = value["data"]["resource"];
  if (typeof resource["path"] !== "string" ||
      !resource["path"].startsWith("/") ||
      resource["path"].startsWith("/_hpd/") ||
      typeof resource["filename"] !== "string" ||
      resource["filename"].trim().length === 0 ||
      typeof resource["mediaType"] !== "string" ||
      resource["mediaType"].trim().length === 0) {
    throw new Error(
      "Penpot export returned invalid resource metadata.",
    );
  }
  return {
    path: resource["path"],
    filename: resource["filename"],
    mediaType: resource["mediaType"],
  };
}

function publishedExportResource(
  value: unknown,
): HpdosPublishedClientToolResource | undefined {
  if (!isRecord(value) ||
      !isRecord(value["data"]) ||
      !isRecord(value["data"]["resource"])) {
    return undefined;
  }
  const resource = value["data"]["resource"];
  if (typeof resource["id"] !== "string" ||
      typeof resource["url"] !== "string" ||
      typeof resource["mimeType"] !== "string" ||
      typeof resource["filename"] !== "string" ||
      typeof resource["expiresAt"] !== "string") {
    return undefined;
  }
  return resource as unknown as HpdosPublishedClientToolResource;
}

async function publishExportResourceToSameOrigin(
  resource: {
    path: string;
    filename: string;
    mediaType: string;
  },
): Promise<HpdosPublishedClientToolResource> {
  if (typeof location === "undefined" ||
      typeof sessionStorage === "undefined") {
    throw new Error(
      "HPD-OS resource publication requires a browser launch.",
    );
  }
  const browserLaunchSessionId = sessionStorage.getItem(
    "hpdos.browserLaunchSessionId",
  );
  if (browserLaunchSessionId === null ||
      !/^bls_[a-f0-9]{32}$/.test(browserLaunchSessionId)) {
    throw new Error(
      "HPD-OS browser launch session identity is unavailable for resource publication.",
    );
  }
  const source = new URL(resource.path, location.origin);
  if (source.origin !== location.origin ||
      source.pathname.startsWith("/_hpd/")) {
    throw new Error(
      "Penpot export resource must use the isolated App origin.",
    );
  }
  const exported = await fetch(source, {
    method: "GET",
    credentials: "include",
    cache: "no-store",
    redirect: "error",
  });
  if (!exported.ok) {
    throw new Error(
      `Penpot export download failed with status ${exported.status}.`,
    );
  }
  const declaredLength = exported.headers.get("content-length");
  if (declaredLength !== null &&
      Number(declaredLength) > 16 * 1024 * 1024) {
    throw new Error(
      "Penpot export exceeds the HPD-OS resource publication bound.",
    );
  }
  const content = await exported.arrayBuffer();
  if (content.byteLength === 0 ||
      content.byteLength > 16 * 1024 * 1024) {
    throw new Error(
      "Penpot export exceeds the HPD-OS resource publication bound.",
    );
  }

  const response = await fetch(
    `/_hpd/resources/${browserLaunchSessionId}`,
    {
      method: "POST",
      credentials: "include",
      cache: "no-store",
      redirect: "error",
      headers: {
        "content-type": resource.mediaType,
        "x-hpdos-resource-filename":
          encodeURIComponent(resource.filename),
      },
      body: content,
    },
  );
  if (!response.ok) {
    throw new Error(
      `HPD-OS resource publication failed with status ${response.status}.`,
    );
  }
  const published =
    await response.json() as HpdosPublishedClientToolResource;
  if (typeof published.id !== "string" ||
      typeof published.url !== "string" ||
      typeof published.mimeType !== "string" ||
      typeof published.filename !== "string" ||
      typeof published.expiresAt !== "string") {
    throw new Error(
      "HPD-OS returned an invalid resource publication.",
    );
  }
  return published;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function clientToolError(error: unknown): {
  kind: string;
  message: string;
  retryable: boolean;
} {
  const message = errorMessage(error);
  const normalized = message.toLowerCase();

  if (normalized.includes("read-only")) {
    return { kind: "permission_denied", message, retryable: false };
  }
  if (normalized.includes("not found")) {
    return { kind: "resource_not_found", message, retryable: false };
  }
  if (normalized.includes("requires the") && normalized.includes("feature")) {
    return { kind: "unsupported_operation", message, retryable: false };
  }
  if (normalized.includes("not observed") || normalized.includes("timed out")) {
    return { kind: "postcondition_failed", message, retryable: true };
  }
  return { kind: "provider_failure", message, retryable: false };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
