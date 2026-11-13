// MapLibre GL 6 is ESM and loads its worker relative to its own module URL, which breaks once bundled.
// Ship the worker as a static file instead; the map calls setWorkerUrl() with this path.
import { copyFileSync, mkdirSync } from "node:fs";

mkdirSync("public/maplibre", { recursive: true });
copyFileSync("node_modules/maplibre-gl/dist/maplibre-gl-worker.mjs", "public/maplibre/maplibre-gl-worker.js");
console.log("copied maplibre-gl-worker.mjs -> public/maplibre/maplibre-gl-worker.js (as .js: some static hosts serve .mjs as text/plain, which browsers refuse for module workers)");
