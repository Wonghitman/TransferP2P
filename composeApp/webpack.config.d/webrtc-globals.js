/**
 * Kotlin/Wasm importObject references browser APIs as free identifiers
 * (e.g. `new RTCPeerConnection(...)`). Webpack ES modules don't resolve those
 * to window/globalThis.
 *
 * Inject module-scoped `const` bindings into composeApp.import-object.mjs.
 * Avoid DefinePlugin(`globalThis.Name`) — when Name is undefined that surfaces
 * as "globalThis.RTCPeerConnection is not a constructor".
 */
const fs = require("fs");
const path = require("path");

const browserGlobals = [
  "RTCPeerConnection",
  "RTCIceCandidate",
  "RTCSessionDescription",
  "RTCDataChannelEvent",
  "RTCPeerConnectionIceEvent",
  "RTCTrackEvent",
  "MediaStream",
  "MediaStreamTrack",
  "MediaStreamTrackEvent",
  "MediaDevices",
  "MediaDeviceInfo",
  "MediaKeys",
  "MediaKeySession",
  "MediaKeyStatusMap",
  "MediaKeySystemAccess",
  "MediaList",
  "MediaQueryList",
  "MediaQueryListEvent",
  "ErrorEvent",
];

const loaderSource = `
const browserGlobals = ${JSON.stringify(browserGlobals)};

module.exports = function webrtcImportObjectLoader(source) {
  const bindings = browserGlobals
    .map((name) => "const " + name + " = globalThis[" + JSON.stringify(name) + "];")
    .join("\\n");

  const guard = [
    'if (typeof RTCPeerConnection !== "function") {',
    '  console.error(',
    '    "[WebRTC] RTCPeerConnection unavailable.",',
    '    "isSecureContext=",',
    '    typeof globalThis.isSecureContext !== "undefined" ? globalThis.isSecureContext : "?",',
    '    "href=",',
    '    typeof location !== "undefined" ? location.href : "?"',
    "  );",
    "}",
  ].join("\\n");

  return bindings + "\\n" + guard + "\\n" + source;
};
`;

// webpack.config.d is evaluated inside build/wasm/packages/composeApp — write loader there.
const loaderPath = path.join(__dirname, "webrtc-import-object-loader.generated.js");
fs.writeFileSync(loaderPath, loaderSource, "utf8");

config.module.rules.push({
  test: /[\\/]composeApp\.import-object\.mjs$/,
  enforce: "pre",
  use: [loaderPath],
});
