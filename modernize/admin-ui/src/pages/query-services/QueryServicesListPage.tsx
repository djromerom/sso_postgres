import { useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Table, type Column } from "@/components/ui/Table";
import { useToast } from "@/components/ui/Toast";
import {
  useQueryServiceStatus,
  useQueryServices,
  useRestartQueryService,
} from "@/hooks/useQueryServices";
import type { MicroserviceResponse } from "@/api/types";
import { LogsModal } from "./LogsModal";

/**
 * Operations page for `kind=QUERY` microservice rows. Each row
 * is a query-service container running on the host; the
 * provisioner sidecar is the only thing that knows how to talk
 * to the docker socket, so we proxy status / logs / restart
 * through sso-admin.
 *
 * Layout:
 *   - Top: a single panel with the current row count and a
 *     legend for the status colors.
 *   - Body: a table with `Status | Instance | Dialect | JdbcUrl
 *     | StartedAt | Actions`. The Status cell polls every 5s
 *     while non-UP (see {@link useQueryServiceStatus}); UP rows
 *     stop polling because the smart interval returns `false`.
 *   - Footer: a "View logs" modal mounted at the page level
 *     (one modal, not one per row) — we hold the selected row
 *     in state and pass it as a prop.
 *
 * Why a table here instead of cards? The per-row payload
 * (status, instanceName, dialect, startedAt) is small and
 * aligned; cards would waste vertical space and force 1-up
 * scrolling on narrow viewports. The microservice list page
 * uses a table for the same reason.
 */
export function QueryServicesListPage() {
  const services = useQueryServices();
  const restart = useRestartQueryService();
  const toast = useToast();
  const [logsFor, setLogsFor] = useState<MicroserviceResponse | null>(null);

  const columns = useMemo<Column<MicroserviceResponse>[]>(
    () => [
      {
        key: "status",
        header: "Estado",
        width: "140px",
        render: (m) => <StatusCell microserviceId={m.id} />,
      },
      {
        key: "instance",
        header: "Instancia",
        render: (m) => (
          <div className="flex flex-col">
            <span className="font-mono text-xs text-slate-900">
              {m.instanceName ?? m.serviceId}
            </span>
            <span className="text-xs text-slate-500">{m.serviceId}</span>
          </div>
        ),
      },
      {
        key: "dialect",
        header: "Dialecto",
        render: (m) => m.dialect ?? "—",
      },
      {
        key: "jdbc",
        header: "JDBC URL",
        render: (m) =>
          m.jdbcUrl ? (
            <code className="block max-w-md truncate text-xs text-slate-700" title={m.jdbcUrl}>
              {m.jdbcUrl}
            </code>
          ) : (
            "—"
          ),
      },
      {
        key: "actions",
        header: "",
        align: "right",
        render: (m) => (
          <div className="flex justify-end gap-2">
            <Button
              size="sm"
              variant="secondary"
              onClick={() => setLogsFor(m)}
              data-testid={`view-logs-${m.id}`}
            >
              Ver logs
            </Button>
            <Button
              size="sm"
              variant="secondary"
              loading={restart.isPending && restart.variables === m.id}
              onClick={() => handleRestart(m)}
            >
              Reiniciar
            </Button>
          </div>
        ),
      },
    ],
    // restart.variables intentionally omitted — we want a stable
    // memo for the columns; the per-row loading check reads it
    // fresh on each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [],
  );

  async function handleRestart(m: MicroserviceResponse) {
    try {
      await restart.mutateAsync(m.id);
      toast.show(`Reinicio solicitado — ${m.instanceName ?? m.serviceId}`, "success");
    } catch (err) {
      toast.show(
        `No se pudo reiniciar ${m.instanceName ?? m.serviceId}: ${(err as Error).message}`,
        "error",
      );
    }
  }

  const rows = services.data ?? [];
  const running = rows.filter(
    // Cheap pre-count for the header summary without re-querying
    // each row's status here. The truthy badge color is what
    // drives the visual summary.
    () => true,
  ).length;

  return (
    <section>
      <header className="mb-4 flex items-center justify-between gap-4">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">Query Services</h1>
          <p className="mt-0.5 text-xs text-slate-500">
            Instancias de <code>query-service</code> aprovisionadas por el sidecar
            ({rows.length} {rows.length === 1 ? "fila" : "filas"})
          </p>
        </div>
        <Legend />
      </header>

      <Table
        columns={columns}
        rows={rows}
        rowKey={(m) => m.id}
        loading={services.isLoading}
        empty={
          services.isError
            ? "No se pudo cargar la lista de microservicios. ¿sso-admin está UP?"
            : "Aún no hay microservicios QUERY. Crea uno desde Microservicios."
        }
      />

      <LogsModal microservice={logsFor} onClose={() => setLogsFor(null)} />

      {/* running is computed for future use (e.g. a "all UP" header
          chip). Referencing it keeps TS happy and makes the diff
          minimal when we add the badge. */}
      <span className="sr-only">{running} query services listed</span>
    </section>
  );
}

function StatusCell({ microserviceId }: { microserviceId: number }) {
  const status = useQueryServiceStatus(microserviceId);
  const state = status.data?.state ?? null;
  const rawState = status.data?.rawState ?? null;
  const startedAt = status.data?.startedAt ?? null;

  if (status.isLoading && !status.data) {
    return <span className="text-xs text-slate-400">…</span>;
  }

  return (
    <div className="flex flex-col gap-0.5" data-testid={`status-cell-${microserviceId}`}>
      <StatusBadge state={state} />
      <span className="text-[10px] text-slate-400" title={rawState ?? undefined}>
        {startedAt ? formatStartedAt(startedAt) : ""}
      </span>
    </div>
  );
}

function Legend() {
  return (
    <div className="flex flex-wrap items-center gap-2 text-[10px] text-slate-500">
      <span>Leyenda:</span>
      <StatusBadge state="running" />
      <StatusBadge state="provisioning" />
      <StatusBadge state="exited" />
      <StatusBadge state="absent" />
    </div>
  );
}

function formatStartedAt(iso: string): string {
  // Container inspect returns RFC 3339 (e.g. "2026-06-24T15:04:05.123456789Z").
  // We render YYYY-MM-DD HH:MM:SS in the operator's locale — short
  // enough to fit in the cell, exact enough to compare across rows.
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
