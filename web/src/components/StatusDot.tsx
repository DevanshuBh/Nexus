import type { VehicleStatus } from "@/lib/types";

const styles: Record<VehicleStatus, string> = {
  moving: "bg-moving",
  idle: "bg-idle",
  stale: "bg-stale",
};

/** Colour plus a text label for screen readers: status is never conveyed by colour alone. */
export function StatusDot({ status }: { status: VehicleStatus }) {
  return (
    <span className="inline-flex items-center">
      <span aria-hidden className={`size-2.5 shrink-0 rounded-full ${styles[status]}`} />
      <span className="sr-only">{status}</span>
    </span>
  );
}
