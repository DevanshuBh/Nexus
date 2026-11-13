"use client";

import { useEffect, useRef } from "react";
import type { GeoJSONSource, Map as MapLibreMap } from "maplibre-gl";
import type { ExpressionSpecification } from "@maplibre/maplibre-gl-style-spec";
import "maplibre-gl/dist/maplibre-gl.css";
import { BASE_PATH, MAP_CENTER, MAP_STYLE_DARK, MAP_STYLE_LIGHT } from "@/lib/config";
import type { FleetStore } from "@/lib/fleet-store";
import type { TrackPoint } from "@/lib/types";

/** At and above this zoom every truck is drawn individually and animated; below it, clusters. */
const DETAIL_ZOOM = 12;

type FC = GeoJSON.FeatureCollection<GeoJSON.Point, { id: string; status: string; selected?: boolean }>;
const empty = (): GeoJSON.FeatureCollection => ({ type: "FeatureCollection", features: [] });

export interface FleetMapProps {
  store: FleetStore;
  selectedId: string | null;
  follow: boolean;
  track: TrackPoint[] | null;
  playbackTs: number | null;
  onSelect: (id: string | null) => void;
  onFps: (fps: number) => void;
  flyTo: { lon: number; lat: number; seq: number } | null;
}

/**
 * The live map. Rendering strategy (measured in docs/benchmarks.md):
 * - Truck positions live in a mutable store, not React state; React never re-renders per update.
 * - Zoomed out: one clustered GeoJSON source, refreshed once per second.
 * - Zoomed in: a second source, rebuilt every animation frame from interpolated positions, culled to the viewport.
 * - setData is asynchronous in MapLibre 6; a frame is skipped while the previous update is still being processed,
 *   so slow devices degrade to a lower update rate instead of queueing work.
 */
export function FleetMap(props: FleetMapProps) {
  const container = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const propsRef = useRef(props);
  propsRef.current = props;

  useEffect(() => {
    let cancelled = false;
    let raf = 0;
    let clusterTimer: ReturnType<typeof setInterval> | undefined;

    void (async () => {
      const ml = await import("maplibre-gl");
      if (cancelled || !container.current) {
        return;
      }
      ml.setWorkerUrl(`${BASE_PATH}/maplibre/maplibre-gl-worker.js`);
      const dark = window.matchMedia("(prefers-color-scheme: dark)").matches;
      const map = new ml.Map({
        container: container.current,
        style: dark ? MAP_STYLE_DARK : MAP_STYLE_LIGHT,
        center: MAP_CENTER,
        zoom: 10.6,
        attributionControl: { compact: true },
      });
      mapRef.current = map;
      map.addControl(new ml.NavigationControl({ showCompass: false }), "top-right");

      map.on("load", () => {
        const color: ExpressionSpecification = [
          "match", ["get", "status"],
          "moving", dark ? "#34d399" : "#059669",
          "idle", dark ? "#fbbf24" : "#d97706",
          /* stale */ dark ? "#94a3b8" : "#64748b",
        ];
        map.addSource("trucks-clustered", { type: "geojson", data: empty(), cluster: true, clusterRadius: 48, clusterMaxZoom: DETAIL_ZOOM - 1 });
        map.addSource("trucks-live", { type: "geojson", data: empty() });
        map.addSource("track", { type: "geojson", data: empty() });
        map.addSource("playback", { type: "geojson", data: empty() });

        map.addLayer({
          id: "track-line", type: "line", source: "track",
          paint: { "line-color": dark ? "#60a5fa" : "#2563eb", "line-width": 3, "line-opacity": 0.85 },
          layout: { "line-cap": "round", "line-join": "round" },
        });
        map.addLayer({
          id: "clusters", type: "circle", source: "trucks-clustered", maxzoom: DETAIL_ZOOM,
          filter: ["has", "point_count"],
          paint: {
            "circle-color": dark ? "#1e3a8a" : "#1d4ed8",
            "circle-opacity": 0.85,
            "circle-radius": ["step", ["get", "point_count"], 14, 50, 18, 200, 24, 1000, 30],
            "circle-stroke-width": 2,
            "circle-stroke-color": dark ? "#93c5fd" : "#ffffff",
          },
        });
        map.addLayer({
          id: "cluster-count", type: "symbol", source: "trucks-clustered", maxzoom: DETAIL_ZOOM,
          filter: ["has", "point_count"],
          layout: { "text-field": ["get", "point_count_abbreviated"], "text-size": 12, "text-font": ["Noto Sans Regular"] },
          paint: { "text-color": "#ffffff" },
        });
        map.addLayer({
          id: "points-low", type: "circle", source: "trucks-clustered", maxzoom: DETAIL_ZOOM,
          filter: ["!", ["has", "point_count"]],
          paint: { "circle-color": color, "circle-radius": 4, "circle-stroke-width": 1, "circle-stroke-color": "#ffffff" },
        });
        map.addLayer({
          id: "points-live", type: "circle", source: "trucks-live", minzoom: DETAIL_ZOOM,
          paint: {
            "circle-color": color,
            "circle-radius": ["case", ["boolean", ["get", "selected"], false], 9, 6],
            "circle-stroke-width": ["case", ["boolean", ["get", "selected"], false], 3, 1.5],
            "circle-stroke-color": ["case", ["boolean", ["get", "selected"], false], dark ? "#f8fafc" : "#0f172a", "#ffffff"],
          },
        });
        map.addLayer({
          id: "playback-point", type: "circle", source: "playback",
          paint: { "circle-color": dark ? "#60a5fa" : "#2563eb", "circle-radius": 8, "circle-stroke-width": 3, "circle-stroke-color": "#ffffff" },
        });

        for (const layer of ["points-low", "points-live"]) {
          map.on("click", layer, (e) => {
            const id = e.features?.[0]?.properties?.id as string | undefined;
            if (id) {
              propsRef.current.onSelect(id);
            }
          });
          map.on("mouseenter", layer, () => (map.getCanvas().style.cursor = "pointer"));
          map.on("mouseleave", layer, () => (map.getCanvas().style.cursor = ""));
        }
        map.on("click", "clusters", async (e) => {
          const f = e.features?.[0];
          const src = map.getSource<GeoJSONSource>("trucks-clustered");
          if (!f || !src) {
            return;
          }
          const zoom = await src.getClusterExpansionZoom(f.properties.cluster_id as number);
          map.easeTo({ center: (f.geometry as GeoJSON.Point).coordinates as [number, number], zoom });
        });

        // Clusters: once per second is plenty, and supercluster re-indexing every frame would be wasteful.
        const clustered = map.getSource<GeoJSONSource>("trucks-clustered")!;
        let clusterBusy = false;
        const refreshClusters = () => {
          if (clusterBusy || map.getZoom() >= DETAIL_ZOOM) {
            return;
          }
          clusterBusy = true;
          clustered.setData(features(propsRef.current.store, performance.now(), null, null)).finally(() => (clusterBusy = false));
        };
        refreshClusters();
        clusterTimer = setInterval(refreshClusters, 1000);
        map.on("zoomend", refreshClusters);

        // Detail layer: every animation frame, viewport only.
        const liveSrc = map.getSource<GeoJSONSource>("trucks-live")!;
        let liveBusy = false;
        let frames = 0;
        let fpsSince = performance.now();
        const frame = () => {
          raf = requestAnimationFrame(frame);
          const now = performance.now();
          frames++;
          if (now - fpsSince >= 1000) {
            propsRef.current.onFps(Math.round((frames * 1000) / (now - fpsSince)));
            frames = 0;
            fpsSince = now;
          }
          const p = propsRef.current;
          if (p.follow && p.selectedId) {
            const v = p.store.vehicles.get(p.selectedId);
            if (v) {
              const at = p.store.positionAt(v, now);
              map.setCenter([at.lon, at.lat]);
            }
          }
          if (!liveBusy && map.getZoom() >= DETAIL_ZOOM) {
            liveBusy = true;
            const b = map.getBounds();
            liveSrc.setData(features(p.store, now, p.selectedId, [b.getWest(), b.getSouth(), b.getEast(), b.getNorth()]))
              .finally(() => (liveBusy = false));
          }
        };
        raf = requestAnimationFrame(frame);
      });
    })();

    return () => {
      cancelled = true;
      cancelAnimationFrame(raf);
      if (clusterTimer) {
        clearInterval(clusterTimer);
      }
      mapRef.current?.remove();
      mapRef.current = null;
    };
  }, []);

  // Route history line.
  useEffect(() => {
    const map = mapRef.current;
    const src = map?.getSource<GeoJSONSource>("track");
    if (!map || !src) {
      return;
    }
    const pts = props.track ?? [];
    void src.setData(pts.length < 2 ? empty() : {
      type: "Feature", properties: {},
      geometry: { type: "LineString", coordinates: pts.map((p) => [p.lon, p.lat]) },
    });
  }, [props.track]);

  // Playback marker.
  useEffect(() => {
    const src = mapRef.current?.getSource<GeoJSONSource>("playback");
    if (!src) {
      return;
    }
    const at = props.track && props.playbackTs !== null ? pointOnTrack(props.track, props.playbackTs) : null;
    void src.setData(at ? { type: "Feature", properties: {}, geometry: { type: "Point", coordinates: [at.lon, at.lat] } } : empty());
  }, [props.track, props.playbackTs]);

  // Fly to (from the list or the alerts feed).
  useEffect(() => {
    if (props.flyTo && mapRef.current) {
      mapRef.current.flyTo({ center: [props.flyTo.lon, props.flyTo.lat], zoom: Math.max(mapRef.current.getZoom(), 14.5), speed: 1.6 });
    }
  }, [props.flyTo]);

  return (
    // MapLibre's CSS sets `position: relative` on its container, so the absolute positioning lives on a wrapper.
    <div className="absolute inset-0">
      <div
        ref={container}
        className="h-full w-full"
        role="region"
        aria-label="Live fleet map. The vehicle list offers the same information for keyboard and screen-reader users."
      />
    </div>
  );
}

function features(store: FleetStore, now: number, selectedId: string | null, bbox: [number, number, number, number] | null): FC {
  const out: FC["features"] = [];
  for (const [id, v] of store.vehicles) {
    const at = store.positionAt(v, now);
    if (bbox && (at.lon < bbox[0] || at.lon > bbox[2] || at.lat < bbox[1] || at.lat > bbox[3])) {
      continue;
    }
    out.push({
      type: "Feature",
      geometry: { type: "Point", coordinates: [at.lon, at.lat] },
      properties: { id, status: store.status(v, now), selected: selectedId === id },
    });
  }
  return { type: "FeatureCollection", features: out };
}

/** Linear interpolation along a track at time ts (track sorted by ts). */
export function pointOnTrack(track: TrackPoint[], ts: number): TrackPoint | null {
  if (track.length === 0) {
    return null;
  }
  if (ts <= track[0]!.ts) {
    return track[0]!;
  }
  for (let i = 1; i < track.length; i++) {
    const b = track[i]!;
    if (ts <= b.ts) {
      const a = track[i - 1]!;
      const f = (ts - a.ts) / Math.max(1, b.ts - a.ts);
      return { ts, lat: a.lat + (b.lat - a.lat) * f, lon: a.lon + (b.lon - a.lon) * f, speedKph: a.speedKph + (b.speedKph - a.speedKph) * f };
    }
  }
  return track[track.length - 1]!;
}
