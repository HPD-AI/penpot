import type { ClientToolProviderToolContext } from "@hpd-research/hpd-agent-client-tools-typescript";

type JsonSchema = Record<string, unknown>;
type Policy = ClientToolProviderToolContext["policy"];

export interface ActionDefinition<
  TProperties extends Record<string, JsonSchema> = Record<string, JsonSchema>,
  TRequired extends readonly string[] = readonly string[],
> {
  description: string;
  properties: TProperties;
  required: TRequired;
  policy: Policy;
  backgroundOwner?: "browser-owned";
}

export interface PenpotToolDefinition<
  TActions extends Record<string, ActionDefinition> =
    Record<string, ActionDefinition>,
> {
  description: string;
  actions: TActions;
}

const synchronous = "SynchronousOnly" as const;
const background = "BackgroundOnly" as const;

const readPolicy: Policy = {
  requiresPermission: false,
  mutatesState: false,
  requiresFreshContext: false,
  destructive: false,
  idempotent: true,
  invocationModePolicy: synchronous,
};

const contextPolicy: Policy = {
  ...readPolicy,
  mutatesState: true,
  idempotent: false,
};

function writePolicy(
  scope: string,
  options: {
    destructive?: boolean;
    idempotent?: boolean;
    background?: boolean;
  } = {},
): Policy {
  return {
    requiresPermission: true,
    permissionScope: scope,
    mutatesState: true,
    requiresFreshContext: true,
    destructive: options.destructive ?? false,
    idempotent: options.idempotent ?? false,
    invocationModePolicy: options.background ? background : synchronous,
  };
}

const string = { type: "string", minLength: 1 } satisfies JsonSchema;
const optionalString = { type: "string" } satisfies JsonSchema;
const number = { type: "number" } satisfies JsonSchema;
const nonNegativeNumber = {
  type: "number",
  minimum: 0,
} satisfies JsonSchema;
const positiveNumber = {
  type: "number",
  exclusiveMinimum: 0,
} satisfies JsonSchema;
const positiveInteger = {
  type: "integer",
  minimum: 1,
} satisfies JsonSchema;
const integer = { type: "integer" } satisfies JsonSchema;
const nonNegativeInteger = {
  type: "integer",
  minimum: 0,
} satisfies JsonSchema;
const boolean = { type: "boolean" } satisfies JsonSchema;
const idArray = {
  type: "array",
  items: string,
  minItems: 1,
  uniqueItems: true,
} satisfies JsonSchema;
const twoOrMoreIds = {
  ...idArray,
  minItems: 2,
} satisfies JsonSchema;
const threeOrMoreIds = {
  ...idArray,
  minItems: 3,
} satisfies JsonSchema;
const depth = {
  type: "integer",
  minimum: 0,
  maximum: 8,
} satisfies JsonSchema;
const gradientStop = closedObject({
  color: string,
  opacity: { type: "number", minimum: 0, maximum: 1 },
  offset: { type: "number", minimum: 0, maximum: 1 },
}, ["color", "offset"]);
const gradient = closedObject({
  type: { enum: ["linear", "radial"] },
  startX: number,
  startY: number,
  endX: number,
  endY: number,
  width: number,
  stops: {
    type: "array",
    items: gradientStop,
    minItems: 1,
  },
}, ["type", "startX", "startY", "endX", "endY", "width", "stops"]);
const color = {
  type: "object",
  properties: {
    color: string,
    opacity: { type: "number", minimum: 0, maximum: 1 },
    gradient,
    imageId: string,
  },
  oneOf: [
    { required: ["color"] },
    { required: ["gradient"] },
    { required: ["imageId"] },
  ],
  additionalProperties: false,
} satisfies JsonSchema;
const shadowColor = closedObject({
  color: string,
  opacity: { type: "number", minimum: 0, maximum: 1 },
}, ["color"]);
const point = closedObject({
  x: number,
  y: number,
}, ["x", "y"]);
const shapeDraft = closedObject({
  name: optionalString,
  x: number,
  y: number,
  width: positiveNumber,
  height: positiveNumber,
  parentId: string,
}, ["x", "y", "width", "height"]);
const radii = closedObject({
  topLeft: nonNegativeNumber,
  topRight: nonNegativeNumber,
  bottomRight: nonNegativeNumber,
  bottomLeft: nonNegativeNumber,
}, ["topLeft", "topRight", "bottomRight", "bottomLeft"]);
const shapePatch = closedObject({
  name: optionalString,
  hidden: boolean,
  blocked: boolean,
  opacity: { type: "number", minimum: 0, maximum: 1 },
  rotation: number,
  blendMode: {
    enum: [
      "normal",
      "darken",
      "multiply",
      "color-burn",
      "lighten",
      "screen",
      "color-dodge",
      "overlay",
      "soft-light",
      "hard-light",
      "difference",
      "exclusion",
      "hue",
      "saturation",
      "color",
      "luminosity",
    ],
  },
  showContent: boolean,
  constraintsH: { enum: ["left", "right", "leftright", "center", "scale"] },
  constraintsV: { enum: ["top", "bottom", "topbottom", "center", "scale"] },
  fixedScroll: boolean,
  radii,
}, [], true);
const textAttrs = closedObject({
  fontFamily: optionalString,
  fontId: optionalString,
  fontVariantId: optionalString,
  fontSize: positiveNumber,
  fontWeight: optionalString,
  fontStyle: optionalString,
  lineHeight: positiveNumber,
  letterSpacing: number,
  textAlign: { enum: ["left", "right", "center", "justify"] },
  textDirection: { enum: ["ltr", "rtl"] },
  textTransform: { enum: ["none", "uppercase", "lowercase", "capitalize"] },
  textDecoration: { enum: ["none", "underline", "line-through"] },
  growType: { enum: ["auto-width", "auto-height", "fixed"] },
}, [], true);
const layoutPatch = closedObject({
  direction: { enum: ["row", "row-reverse", "column", "column-reverse"] },
  wrap: { enum: ["wrap", "nowrap"] },
  gap: closedObject({
    row: nonNegativeNumber,
    column: nonNegativeNumber,
  }, ["row", "column"]),
  padding: closedObject({
    top: nonNegativeNumber,
    right: nonNegativeNumber,
    bottom: nonNegativeNumber,
    left: nonNegativeNumber,
  }, ["top", "right", "bottom", "left"]),
  alignItems: { enum: ["start", "end", "center", "stretch"] },
  justifyContent: {
    enum: [
      "start",
      "center",
      "end",
      "space-between",
      "space-around",
      "space-evenly",
      "stretch",
    ],
  },
  alignContent: {
    enum: [
      "start",
      "end",
      "center",
      "space-between",
      "space-around",
      "space-evenly",
      "stretch",
    ],
  },
  justifyItems: { enum: ["start", "end", "center", "stretch"] },
}, [], true);
const layoutChildPatch = closedObject({
  horizontalSizing: { enum: ["fill", "fix", "auto"] },
  verticalSizing: { enum: ["fill", "fix", "auto"] },
  alignSelf: { enum: ["start", "end", "center", "stretch"] },
  absolute: boolean,
  zIndex: integer,
  margin: closedObject({
    top: number,
    right: number,
    bottom: number,
    left: number,
  }, ["top", "right", "bottom", "left"]),
}, [], true);
const gridTrackValue = {
  type: "object",
  properties: {
    type: { enum: ["percent", "flex", "auto", "fixed"] },
    value: number,
  },
  oneOf: [
    closedObject({ type: { const: "auto" } }, ["type"]),
    closedObject({
      type: { enum: ["percent", "flex", "fixed"] },
      value: number,
    }, ["type", "value"]),
  ],
  required: ["type"],
  additionalProperties: false,
} satisfies JsonSchema;
const pathCommand = closedObject({
  command: {
    enum: [
      "M", "m", "move", "move-to",
      "L", "l", "line", "line-to",
      "C", "c", "curve", "curve-to",
      "Z", "z", "close", "close-path",
    ],
  },
  params: {
    type: "array",
    items: number,
    maxItems: 6,
  },
}, ["command", "params"]);
const interaction = closedObject({
  eventType: string,
  actionType: string,
  destinationId: string,
  delay: nonNegativeNumber,
  preserveScrollPosition: boolean,
  overlayPositionType: optionalString,
  closeOnClickOutside: boolean,
  backgroundOverlay: boolean,
}, ["eventType", "actionType"]);
const exportSpec = closedObject({
  type: { enum: ["png", "jpeg", "svg", "pdf"] },
  scale: positiveNumber,
  suffix: optionalString,
}, ["type"]);

export const penpotToolDefinitions = {
  inspect: tool(
    "Inspect the active file, pages, selection, shapes, assets, and design metadata.",
    {
      getContext: action("Gets the live Penpot provider context.", {}, [], readPolicy),
      getSelection: action("Gets bounded summaries of selected shapes.", { depth }, [], readPolicy),
      listPages: action("Lists pages in file order.", {}, [], readPolicy),
      getPageTree: action("Gets a bounded page shape tree.", {
        pageId: string,
        depth,
      }, [], readPolicy),
      getShape: action("Gets one shape by ID.", {
        shapeId: string,
        depth,
      }, ["shapeId"], readPolicy),
      queryShapes: action("Queries shapes on one page.", {
        pageId: string,
        name: optionalString,
        types: {
          type: "array",
          items: optionalString,
          uniqueItems: true,
        },
        limit: { type: "integer", minimum: 1, maximum: 500 },
      }, [], readPolicy),
      getRecentChanges: action("Gets bounded recent workspace change summaries.", {
        limit: { type: "integer", minimum: 1, maximum: 100 },
      }, [], readPolicy),
    },
  ),

  document: tool("Creates, opens, duplicates, renames, reorders, and deletes pages.", {
    createPage: action("Creates a page.", { name: optionalString }, [], writePolicy("penpot.document.createPage")),
    openPage: action("Makes a page active.", { pageId: string }, ["pageId"], contextPolicy),
    duplicatePage: action("Duplicates a page and its component relationships.", { pageId: string }, ["pageId"], writePolicy("penpot.document.duplicatePage")),
    renamePage: action("Renames a page.", { pageId: string, name: string }, ["pageId", "name"], writePolicy("penpot.document.renamePage")),
    reorderPage: action("Moves a page to a new index.", { pageId: string, index: nonNegativeInteger }, ["pageId", "index"], writePolicy("penpot.document.reorderPage")),
    deletePage: action("Deletes a page.", { pageId: string }, ["pageId"], writePolicy("penpot.document.deletePage", { destructive: true })),
  }),

  shapes: tool("Creates and changes core Penpot shapes.", {
    createRectangle: action("Creates a rectangle.", { shape: shapeDraft }, ["shape"], writePolicy("penpot.shapes.create")),
    createBoard: action("Creates a board.", { shape: shapeDraft }, ["shape"], writePolicy("penpot.shapes.create")),
    createEllipse: action("Creates an ellipse.", { shape: shapeDraft }, ["shape"], writePolicy("penpot.shapes.create")),
    createText: action("Creates a text shape.", {
      shape: shapeDraft,
      text: optionalString,
      attributes: textAttrs,
    }, ["shape", "text"], writePolicy("penpot.shapes.create")),
    patch: action("Patches supported semantic shape properties.", {
      shapeIds: idArray,
      patch: shapePatch,
    }, ["shapeIds", "patch"], writePolicy("penpot.shapes.patch")),
    clone: action("Clones shapes.", {
      shapeIds: idArray,
      delta: point,
    }, ["shapeIds"], writePolicy("penpot.shapes.clone")),
    delete: action("Deletes shapes and descendants.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.shapes.delete", { destructive: true })),
    reparent: action("Moves shapes into a container.", {
      shapeIds: idArray,
      parentId: string,
      index: nonNegativeInteger,
    }, ["shapeIds", "parentId"], writePolicy("penpot.shapes.reparent")),
    setSelection: action("Sets the active shape selection.", { shapeIds: idArray }, ["shapeIds"], contextPolicy),
    clearSelection: action("Clears the shape selection.", {}, [], contextPolicy),
  }),

  transform: tool("Moves, resizes, rotates, flips, aligns, and distributes shapes.", {
    move: action("Moves shapes by a delta.", { shapeIds: idArray, delta: point }, ["shapeIds", "delta"], writePolicy("penpot.transform.move")),
    setPosition: action("Sets one shape position.", { shapeId: string, x: number, y: number }, ["shapeId", "x", "y"], writePolicy("penpot.transform.setPosition")),
    resize: action("Resizes shapes.", { shapeIds: idArray, width: positiveNumber, height: positiveNumber }, ["shapeIds", "width", "height"], writePolicy("penpot.transform.resize")),
    rotate: action("Rotates shapes by degrees.", { shapeIds: idArray, degrees: number }, ["shapeIds", "degrees"], writePolicy("penpot.transform.rotate")),
    flipHorizontal: action("Flips shapes horizontally.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.transform.flip")),
    flipVertical: action("Flips shapes vertically.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.transform.flip")),
    align: action("Aligns shapes.", { shapeIds: idArray, alignment: { enum: ["left", "center", "right", "top", "middle", "bottom"] } }, ["shapeIds", "alignment"], writePolicy("penpot.transform.align")),
    distribute: action("Distributes shapes.", { shapeIds: threeOrMoreIds, axis: { enum: ["horizontal", "vertical"] } }, ["shapeIds", "axis"], writePolicy("penpot.transform.distribute")),
    fitContent: action("Fits boards to their content.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.transform.fitContent")),
  }),

  hierarchy: tool("Groups, masks, and changes shape stacking order.", {
    group: action("Groups shapes.", { shapeIds: idArray, name: optionalString }, ["shapeIds"], writePolicy("penpot.hierarchy.group")),
    ungroup: action("Ungroups containers.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.hierarchy.ungroup")),
    mask: action("Creates masks from shapes.", { shapeIds: twoOrMoreIds }, ["shapeIds"], writePolicy("penpot.hierarchy.mask")),
    unmask: action("Removes masks.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.hierarchy.unmask")),
    bringForward: action("Moves shapes one stacking step forward.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.hierarchy.reorder")),
    bringToFront: action("Moves shapes to the front.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.hierarchy.reorder")),
    sendBackward: action("Moves shapes one stacking step backward.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.hierarchy.reorder")),
    sendToBack: action("Moves shapes to the back.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.hierarchy.reorder")),
    boardFromSelection: action("Creates a board around shapes.", { shapeIds: idArray, name: optionalString }, ["shapeIds"], writePolicy("penpot.hierarchy.boardFromSelection")),
  }),

  vector: tool("Creates and edits paths and boolean shapes.", {
    createPath: action("Creates a path from move, line, cubic-curve, and close commands.", { name: optionalString, commands: { type: "array", items: pathCommand, minItems: 1 } }, ["commands"], writePolicy("penpot.vector.createPath")),
    patchPath: action("Replaces path commands with move, line, cubic-curve, and close commands.", { shapeId: string, commands: { type: "array", items: pathCommand, minItems: 1 } }, ["shapeId", "commands"], writePolicy("penpot.vector.patchPath")),
    convertToPath: action("Converts shapes to paths.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.vector.convert")),
    strokesToPath: action("Converts strokes to paths.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.vector.convert")),
    createBoolean: action("Combines shapes as a boolean.", { shapeIds: twoOrMoreIds, booleanType: { enum: ["union", "difference", "intersection", "exclude"] } }, ["shapeIds", "booleanType"], writePolicy("penpot.vector.boolean")),
    setBooleanType: action("Changes a boolean operation.", { shapeId: string, booleanType: { enum: ["union", "difference", "intersection", "exclude"] } }, ["shapeId", "booleanType"], writePolicy("penpot.vector.boolean")),
    booleanToGroup: action("Converts a boolean to a group.", { shapeId: string }, ["shapeId"], writePolicy("penpot.vector.boolean")),
  }),

  text: tool("Reads and edits text content and typography.", {
    getContent: action("Gets structured text content.", { shapeId: string }, ["shapeId"], readPolicy),
    replaceContent: action("Replaces complete text content.", { shapeId: string, text: optionalString }, ["shapeId", "text"], writePolicy("penpot.text.replaceContent")),
    replaceText: action("Replaces matching text in shapes.", { shapeIds: idArray, search: string, replacement: optionalString }, ["shapeIds", "search", "replacement"], writePolicy("penpot.text.replaceText")),
    patchAttributes: action("Patches text attributes.", { shapeIds: idArray, attributes: textAttrs }, ["shapeIds", "attributes"], writePolicy("penpot.text.patchAttributes")),
    patchRange: action("Patches a text character range.", { shapeId: string, start: nonNegativeInteger, end: nonNegativeInteger, attributes: textAttrs }, ["shapeId", "start", "end", "attributes"], writePolicy("penpot.text.patchRange")),
    applyTypography: action("Applies a typography asset.", { shapeIds: idArray, typographyId: string, libraryId: string }, ["shapeIds", "typographyId"], writePolicy("penpot.text.applyTypography")),
  }),

  layout: tool("Creates and edits flex and grid layouts.", {
    createFlex: action("Creates a flex layout.", { shapeId: string }, ["shapeId"], writePolicy("penpot.layout.create")),
    createGrid: action("Creates a grid layout.", { shapeId: string }, ["shapeId"], writePolicy("penpot.layout.create")),
    remove: action("Removes layout behavior.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.layout.remove")),
    patchContainer: action("Patches layout container properties.", { shapeIds: idArray, patch: layoutPatch }, ["shapeIds", "patch"], writePolicy("penpot.layout.patchContainer")),
    patchChild: action("Patches layout child properties.", { shapeIds: idArray, patch: layoutChildPatch }, ["shapeIds", "patch"], writePolicy("penpot.layout.patchChild")),
    addTrack: action("Adds a grid track.", { shapeId: string, trackType: { enum: ["row", "column"] }, value: gridTrackValue, index: nonNegativeInteger }, ["shapeId", "trackType", "value"], writePolicy("penpot.layout.track")),
    removeTrack: action("Removes a grid track.", { shapeId: string, trackType: { enum: ["row", "column"] }, index: nonNegativeInteger, deleteShapes: boolean }, ["shapeId", "trackType", "index"], writePolicy("penpot.layout.track", { destructive: true })),
    duplicateTrack: action("Duplicates a grid track and content.", { shapeId: string, trackType: { enum: ["row", "column"] }, index: nonNegativeInteger }, ["shapeId", "trackType", "index"], writePolicy("penpot.layout.track")),
    reorderTrack: action("Reorders a grid track.", { shapeId: string, trackType: { enum: ["row", "column"] }, fromIndex: nonNegativeInteger, toIndex: nonNegativeInteger, moveContent: boolean }, ["shapeId", "trackType", "fromIndex", "toIndex"], writePolicy("penpot.layout.track")),
    patchTrack: action("Patches a grid track.", { shapeId: string, trackType: { enum: ["row", "column"] }, index: nonNegativeInteger, value: gridTrackValue }, ["shapeId", "trackType", "index", "value"], writePolicy("penpot.layout.track")),
    patchCells: action("Patches grid cells.", { shapeId: string, cellIds: idArray, patch: closedObject({ alignSelf: { enum: ["auto", "start", "center", "end", "stretch"] }, justifySelf: { enum: ["auto", "start", "center", "end", "stretch"] }, areaName: optionalString, position: closedObject({ row: nonNegativeInteger, column: nonNegativeInteger, rowSpan: positiveInteger, columnSpan: positiveInteger }, ["row", "column", "rowSpan", "columnSpan"]) }, [], true) }, ["shapeId", "cellIds", "patch"], writePolicy("penpot.layout.cells")),
    mergeCells: action("Merges grid cells.", { shapeId: string, cellIds: idArray }, ["shapeId", "cellIds"], writePolicy("penpot.layout.cells")),
    createCellBoard: action("Creates a board from grid cells.", { shapeId: string, cellIds: idArray }, ["shapeId", "cellIds"], writePolicy("penpot.layout.cells")),
  }),

  styles: tool("Edits fills, strokes, shadows, opacity, radii, and style assets.", {
    setFills: action("Replaces fills.", { shapeIds: idArray, fills: { type: "array", items: color } }, ["shapeIds", "fills"], writePolicy("penpot.styles.fills")),
    addFill: action("Adds a fill.", { shapeIds: idArray, fill: color }, ["shapeIds", "fill"], writePolicy("penpot.styles.fills")),
    removeFill: action("Removes a fill.", { shapeIds: idArray, index: nonNegativeInteger }, ["shapeIds", "index"], writePolicy("penpot.styles.fills", { destructive: true })),
    setStrokes: action("Replaces strokes.", { shapeIds: idArray, strokes: { type: "array", items: closedObject({ color, width: nonNegativeNumber, style: { enum: ["solid", "dotted", "dashed", "mixed"] }, alignment: { enum: ["center", "inner", "outer"] } }, ["color"]) } }, ["shapeIds", "strokes"], writePolicy("penpot.styles.strokes")),
    addStroke: action("Adds a stroke.", { shapeIds: idArray, stroke: closedObject({ color, width: nonNegativeNumber, style: { enum: ["solid", "dotted", "dashed", "mixed"] }, alignment: { enum: ["center", "inner", "outer"] } }, ["color"]) }, ["shapeIds", "stroke"], writePolicy("penpot.styles.strokes")),
    removeStroke: action("Removes a stroke.", { shapeIds: idArray, index: nonNegativeInteger }, ["shapeIds", "index"], writePolicy("penpot.styles.strokes", { destructive: true })),
    addShadow: action("Adds a shadow.", { shapeIds: idArray, shadow: closedObject({ x: number, y: number, blur: nonNegativeNumber, spread: number, color: shadowColor, style: { enum: ["drop-shadow", "inner-shadow"] }, hidden: boolean }, ["x", "y", "blur", "spread", "color", "style"]) }, ["shapeIds", "shadow"], writePolicy("penpot.styles.shadows")),
    patchShadow: action("Patches a shadow.", { shapeIds: idArray, index: nonNegativeInteger, shadow: closedObject({ x: number, y: number, blur: nonNegativeNumber, spread: number, color: shadowColor, hidden: boolean }, [], true) }, ["shapeIds", "index", "shadow"], writePolicy("penpot.styles.shadows")),
    removeShadow: action("Removes a shadow.", { shapeIds: idArray, index: nonNegativeInteger }, ["shapeIds", "index"], writePolicy("penpot.styles.shadows", { destructive: true })),
    setOpacity: action("Sets opacity.", { shapeIds: idArray, opacity: { type: "number", minimum: 0, maximum: 1 } }, ["shapeIds", "opacity"], writePolicy("penpot.styles.opacity")),
    setRadii: action("Sets corner radii.", { shapeIds: idArray, radii }, ["shapeIds", "radii"], writePolicy("penpot.styles.radii")),
    applyColorAsset: action("Applies a color library asset.", { shapeIds: idArray, colorId: string, libraryId: string, target: { enum: ["fill", "stroke", "text"] } }, ["shapeIds", "colorId", "target"], writePolicy("penpot.styles.colorAsset")),
  }),

  components: tool("Creates and manages components and variants.", {
    create: action("Creates a component from shapes.", { shapeIds: idArray, name: optionalString }, ["shapeIds"], writePolicy("penpot.components.create")),
    createMultiple: action("Creates components from multiple shapes.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.components.create")),
    instantiate: action("Instantiates a component.", { componentId: string, libraryId: string, position: point }, ["componentId", "position"], writePolicy("penpot.components.instantiate")),
    detach: action("Detaches component instances.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.components.detach", { destructive: true })),
    reset: action("Resets component overrides.", { shapeIds: idArray }, ["shapeIds"], writePolicy("penpot.components.reset")),
    swap: action("Swaps component instances.", { shapeIds: idArray, componentId: string, libraryId: string }, ["shapeIds", "componentId"], writePolicy("penpot.components.swap")),
    duplicate: action("Duplicates a component definition.", { componentId: string, libraryId: string }, ["componentId"], writePolicy("penpot.components.duplicate")),
    rename: action("Renames a component.", { componentId: string, name: string }, ["componentId", "name"], writePolicy("penpot.components.rename")),
    delete: action("Deletes a component.", { componentId: string }, ["componentId"], writePolicy("penpot.components.delete", { destructive: true })),
    restore: action("Restores a deleted component.", { componentId: string }, ["componentId"], writePolicy("penpot.components.restore")),
    combineVariants: action("Combines components as variants.", { shapeIds: twoOrMoreIds }, ["shapeIds"], writePolicy("penpot.components.variants")),
    addVariant: action("Adds a variant.", { shapeId: string }, ["shapeId"], writePolicy("penpot.components.variants")),
    renameVariant: action("Renames a variant.", { shapeId: string, name: string }, ["shapeId", "name"], writePolicy("penpot.components.variants")),
    reorderVariantProperties: action("Reorders variant properties.", { variantId: string, fromIndex: nonNegativeInteger, toIndex: nonNegativeInteger }, ["variantId", "fromIndex", "toIndex"], writePolicy("penpot.components.variants")),
    switchVariant: action("Switches component instances to a variant.", { shapeIds: idArray, componentId: string }, ["shapeIds", "componentId"], writePolicy("penpot.components.variants")),
  }),

  tokens: tool("Reads and manages design tokens, sets, themes, and applications.", {
    list: action("Lists tokens, sets, and themes.", {}, [], readPolicy),
    create: action("Creates a token.", { name: string, tokenType: string, value: {}, setName: string }, ["name", "tokenType", "value"], writePolicy("penpot.tokens.create")),
    update: action("Updates a token.", { name: string, newName: optionalString, value: {}, description: optionalString }, ["name"], writePolicy("penpot.tokens.update")),
    delete: action("Deletes a token.", { name: string }, ["name"], writePolicy("penpot.tokens.delete", { destructive: true })),
    apply: action("Applies tokens to shapes.", { shapeIds: idArray, tokenNames: { type: "array", items: string, minItems: 1, uniqueItems: true }, attributes: { type: "array", items: string, minItems: 1, uniqueItems: true } }, ["shapeIds", "tokenNames", "attributes"], writePolicy("penpot.tokens.apply")),
    unapply: action("Removes token applications.", { shapeIds: idArray, tokenNames: { type: "array", items: string, minItems: 1, uniqueItems: true }, attributes: { type: "array", items: string, minItems: 1, uniqueItems: true } }, ["shapeIds", "attributes"], writePolicy("penpot.tokens.unapply")),
    import: action("Imports token data.", { data: string, format: { enum: ["json", "json5", "tokens-studio"] } }, ["data", "format"], writePolicy("penpot.tokens.import", { background: true })),
    export: action("Exports token data.", { format: { enum: ["json", "json5", "tokens-studio"] } }, ["format"], readPolicy),
    remap: action("Remaps token names.", { mappings: { type: "array", items: closedObject({ from: string, to: string }, ["from", "to"]), minItems: 1 } }, ["mappings"], writePolicy("penpot.tokens.remap")),
    propagate: action("Propagates changed token values.", { tokenNames: { type: "array", items: string, minItems: 1, uniqueItems: true } }, ["tokenNames"], writePolicy("penpot.tokens.propagate")),
  }),

  prototype: tool("Reads and edits flows and prototype interactions.", {
    listFlows: action("Lists prototype flows.", {}, [], readPolicy),
    addFlow: action("Adds a flow.", { boardId: string, name: optionalString }, ["boardId"], writePolicy("penpot.prototype.flows")),
    updateFlow: action("Updates a flow.", { flowId: string, name: optionalString, boardId: string }, ["flowId"], writePolicy("penpot.prototype.flows")),
    removeFlow: action("Removes a flow.", { flowId: string }, ["flowId"], writePolicy("penpot.prototype.flows", { destructive: true })),
    addInteraction: action("Adds an interaction.", { shapeId: string, interaction }, ["shapeId", "interaction"], writePolicy("penpot.prototype.interactions")),
    updateInteraction: action("Updates an interaction.", { shapeId: string, index: nonNegativeInteger, interaction }, ["shapeId", "index", "interaction"], writePolicy("penpot.prototype.interactions")),
    removeInteraction: action("Removes an interaction.", { shapeId: string, index: nonNegativeInteger }, ["shapeId", "index"], writePolicy("penpot.prototype.interactions", { destructive: true })),
  }),

  collaboration: tool("Reads and manages comments in the active file.", {
    listComments: action("Lists comment threads.", { pageId: string, resolved: boolean }, [], readPolicy),
    createComment: action("Creates a comment thread.", { pageId: string, position: point, content: string }, ["pageId", "position", "content"], writePolicy("penpot.collaboration.createComment")),
    reply: action("Replies to a comment thread.", { threadId: string, content: string }, ["threadId", "content"], writePolicy("penpot.collaboration.reply")),
    updateComment: action("Updates a comment.", { commentId: string, content: string }, ["commentId", "content"], writePolicy("penpot.collaboration.updateComment")),
    resolveThread: action("Changes thread resolution.", { threadId: string, resolved: boolean }, ["threadId", "resolved"], writePolicy("penpot.collaboration.resolve")),
    deleteComment: action("Deletes a comment.", { commentId: string }, ["commentId"], writePolicy("penpot.collaboration.deleteComment", { destructive: true })),
  }),

  assets_output: tool("Manages exports, media, and generated output.", {
    listAssets: action("Lists local colors, typographies, components, and media.", { assetType: { enum: ["all", "colors", "typographies", "components", "media"] } }, [], readPolicy),
    exportShapes: action("Exports shapes.", { shapeIds: idArray, exports: { type: "array", items: exportSpec, minItems: 1 } }, ["shapeIds", "exports"], writePolicy("penpot.output.export", { background: true })),
    setExports: action("Sets shape export presets.", { shapeIds: idArray, exports: { type: "array", items: exportSpec } }, ["shapeIds", "exports"], writePolicy("penpot.output.presets")),
    uploadMediaUrl: action("Uploads media from a URL.", { url: string, name: optionalString, position: point }, ["url"], writePolicy("penpot.output.upload", { background: true })),
    createSvg: action("Creates shapes from SVG.", { svg: string, name: optionalString, position: point }, ["svg"], writePolicy("penpot.output.createSvg")),
    createMediaComponent: action("Creates a component from uploaded media.", { mediaId: string, name: optionalString, position: point }, ["mediaId"], writePolicy("penpot.output.mediaComponent")),
  }),
} satisfies Record<string, PenpotToolDefinition>;

export type PenpotToolName = keyof typeof penpotToolDefinitions;
type PenpotOperationRequestsByTool = {
  [TTool in PenpotToolName]:
    OperationRequestForDefinition<(typeof penpotToolDefinitions)[TTool]>;
};

export type PenpotOperationRequest<
  TTool extends PenpotToolName = PenpotToolName,
> = PenpotOperationRequestsByTool[TTool];

export function parametersSchema(definition: PenpotToolDefinition): JsonSchema {
  return {
    type: "object",
    oneOf: Object.entries(definition.actions).map(([name, actionDefinition]) => ({
      description: actionDefinition.description,
      type: "object",
      properties: {
        action: { const: name },
        ...actionDefinition.properties,
      },
      required: ["action", ...(actionDefinition.required ?? [])],
      additionalProperties: false,
    })),
  };
}

export function actionPolicies(
  definition: PenpotToolDefinition,
): Record<string, Policy> {
  return Object.fromEntries(
    Object.entries(definition.actions).map(([name, value]) => [name, value.policy]),
  );
}

export function parseOperationRequest<TDefinition extends PenpotToolDefinition>(
  definition: TDefinition,
  value: unknown,
): OperationRequestForDefinition<TDefinition> {
  if (!isRecord(value) || typeof value["action"] !== "string") {
    throw new Error("Operation request must contain an action.");
  }

  const actionDefinition = definition.actions[value["action"]];
  if (actionDefinition === undefined) {
    throw new Error(`Unsupported action '${value["action"]}'.`);
  }

  const properties = actionDefinition.properties ?? {};
  const allowed = new Set(["action", ...Object.keys(properties), "invocationMode"]);
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) {
      throw new Error(`Unexpected operation request field '${key}'.`);
    }
  }
  for (const key of actionDefinition.required ?? []) {
    if (!(key in value)) {
      throw new Error(`Required operation request field '${key}' is missing.`);
    }
  }
  for (const [key, schema] of Object.entries(properties)) {
    if (value[key] !== undefined) {
      validateSchema(value[key], schema, key);
    }
  }
  return value as OperationRequestForDefinition<TDefinition>;
}

function tool<const TActions extends Record<string, ActionDefinition>>(
  description: string,
  actions: TActions,
): PenpotToolDefinition<TActions> {
  return { description, actions };
}

function action<
  const TProperties extends Record<string, JsonSchema>,
  const TRequired extends readonly (keyof TProperties & string)[],
>(
  description: string,
  properties: TProperties,
  required: TRequired,
  policy: Policy,
): ActionDefinition<TProperties, TRequired> {
  return {
    description,
    properties,
    required,
    policy,
    ...(policy.invocationModePolicy === background
      ? { backgroundOwner: "browser-owned" as const }
      : {}),
  };
}

type OperationRequestForDefinition<TDefinition extends PenpotToolDefinition> = {
  [TAction in keyof TDefinition["actions"] & string]:
    ActionRequest<TAction, TDefinition["actions"][TAction]>;
}[keyof TDefinition["actions"] & string];

type ActionRequest<
  TAction extends string,
  TDefinition extends ActionDefinition,
> = {
  action: TAction;
  invocationMode?: string;
} & RequiredProperties<TDefinition>
  & OptionalProperties<TDefinition>;

type RequiredProperties<TDefinition extends ActionDefinition> = {
  [TKey in RequiredPropertyKeys<TDefinition>]:
    SchemaValue<TDefinition["properties"][TKey]>;
};

type OptionalProperties<TDefinition extends ActionDefinition> = {
  [TKey in Exclude<
    keyof TDefinition["properties"],
    RequiredPropertyKeys<TDefinition>
  >]?: SchemaValue<TDefinition["properties"][TKey]>;
};

type RequiredPropertyKeys<TDefinition extends ActionDefinition> = Extract<
  TDefinition["required"][number],
  keyof TDefinition["properties"]
>;

type SchemaValue<TSchema> =
  TSchema extends { enum: readonly (infer TValue)[] }
    ? TValue
    : TSchema extends { type: "string" }
      ? string
      : TSchema extends { type: "number" | "integer" }
        ? number
        : TSchema extends { type: "boolean" }
          ? boolean
          : TSchema extends { type: "array"; items: infer TItem }
            ? SchemaValue<TItem>[]
            : TSchema extends {
                type: "object";
                properties: infer TProperties extends Record<string, unknown>;
                required?: infer TRequired extends readonly string[];
              }
              ? ObjectSchemaValue<TProperties, TRequired>
              : unknown;

type ObjectSchemaValue<
  TProperties extends Record<string, unknown>,
  TRequired extends readonly string[],
> = {
  [TKey in Extract<TRequired[number], keyof TProperties>]:
    SchemaValue<TProperties[TKey]>;
} & {
  [TKey in Exclude<
    keyof TProperties,
    TRequired[number]
  >]?: SchemaValue<TProperties[TKey]>;
};

function closedObject(
  properties: Record<string, JsonSchema>,
  required: readonly string[] = [],
  nonEmpty = false,
): JsonSchema {
  return {
    type: "object",
    properties,
    required,
    ...(nonEmpty ? { minProperties: 1 } : {}),
    additionalProperties: false,
  };
}

function validateSchema(value: unknown, schema: JsonSchema, path: string): void {
  if (Array.isArray(schema["oneOf"])) {
    const matches = schema["oneOf"].filter((branch) => {
      if (!isRecord(branch)) {
        return false;
      }
      try {
        validateSchema(value, branch, path);
        return true;
      } catch {
        return false;
      }
    });
    if (matches.length !== 1) {
      throw new Error(`${path} must match exactly one supported shape.`);
    }
  }
  if ("const" in schema && value !== schema["const"]) {
    throw new Error(`${path} must equal '${String(schema["const"])}'.`);
  }
  if (Array.isArray(schema["enum"]) && !schema["enum"].includes(value)) {
    throw new Error(`${path} has an unsupported value.`);
  }

  switch (schema["type"]) {
    case undefined:
      validateRequiredProperties(value, schema, path);
      return;
    case "string":
      if (typeof value !== "string") {
        throw new Error(`${path} must be a string.`);
      }
      if (typeof schema["minLength"] === "number" && value.length < schema["minLength"]) {
        throw new Error(`${path} is too short.`);
      }
      return;
    case "number":
      if (typeof value !== "number" || !Number.isFinite(value)) {
        throw new Error(`${path} must be a finite number.`);
      }
      validateNumber(value, schema, path);
      return;
    case "integer":
      if (!Number.isInteger(value)) {
        throw new Error(`${path} must be an integer.`);
      }
      validateNumber(value as number, schema, path);
      return;
    case "boolean":
      if (typeof value !== "boolean") {
        throw new Error(`${path} must be a boolean.`);
      }
      return;
    case "array": {
      if (!Array.isArray(value)) {
        throw new Error(`${path} must be an array.`);
      }
      if (typeof schema["minItems"] === "number" && value.length < schema["minItems"]) {
        throw new Error(`${path} has too few items.`);
      }
      if (typeof schema["maxItems"] === "number" && value.length > schema["maxItems"]) {
        throw new Error(`${path} has too many items.`);
      }
      if (schema["uniqueItems"] === true && new Set(value.map(stableKey)).size !== value.length) {
        throw new Error(`${path} must contain unique items.`);
      }
      const itemSchema = schema["items"];
      if (isRecord(itemSchema)) {
        value.forEach((item, index) => validateSchema(item, itemSchema, `${path}[${index}]`));
      }
      return;
    }
    case "object": {
      if (!isRecord(value)) {
        throw new Error(`${path} must be an object.`);
      }
      if (
        typeof schema["minProperties"] === "number" &&
        Object.keys(value).length < schema["minProperties"]
      ) {
        throw new Error(`${path} must contain at least one property.`);
      }
      const properties = isRecord(schema["properties"]) ? schema["properties"] : {};
      const required = Array.isArray(schema["required"]) ? schema["required"] : [];
      if (schema["additionalProperties"] === false) {
        for (const key of Object.keys(value)) {
          if (!(key in properties)) {
            throw new Error(`${path}.${key} is not supported.`);
          }
        }
      }
      for (const key of required) {
        if (typeof key === "string" && !(key in value)) {
          throw new Error(`${path}.${key} is required.`);
        }
      }
      for (const [key, childSchema] of Object.entries(properties)) {
        if (value[key] !== undefined && isRecord(childSchema)) {
          validateSchema(value[key], childSchema, `${path}.${key}`);
        }
      }
      return;
    }
    default:
      throw new Error(`${path} uses an unsupported schema type.`);
  }
}

function validateRequiredProperties(
  value: unknown,
  schema: JsonSchema,
  path: string,
): void {
  const required = Array.isArray(schema["required"]) ? schema["required"] : [];
  if (!isRecord(value)) {
    if (required.length > 0) {
      throw new Error(`${path} must be an object.`);
    }
    return;
  }
  for (const key of required) {
    if (typeof key === "string" && !(key in value)) {
      throw new Error(`${path}.${key} is required.`);
    }
  }
}

function validateNumber(value: number, schema: JsonSchema, path: string): void {
  if (typeof schema["minimum"] === "number" && value < schema["minimum"]) {
    throw new Error(`${path} is below its minimum.`);
  }
  if (typeof schema["maximum"] === "number" && value > schema["maximum"]) {
    throw new Error(`${path} is above its maximum.`);
  }
  if (
    typeof schema["exclusiveMinimum"] === "number" &&
    value <= schema["exclusiveMinimum"]
  ) {
    throw new Error(`${path} must be greater than its minimum.`);
  }
}

function stableKey(value: unknown): string {
  return typeof value === "string" ? value : JSON.stringify(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
