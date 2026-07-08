import { useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { StatusBadge } from "@/components/ui/StatusBadge";
import { Table, type Column } from "@/components/ui/Table";
import { useToast } from "@/components/ui/Toast";
import type { MicroserviceResponse } from "@/api/types";
import { useQueryServiceStatus, useRestartQueryService } from "@/hooks/useQueryServices";
import { LogsModal } from "@/pages/query-services/LogsModal";

type QueryServicesPanelProps = {
  rows: MicroserviceResponse[];
};

export function QueryServicesPanel({ rows }: QueryServicesPanelProps) {
  const services = useMemo(() => rows.filter((m) => m.kind === "QUERY"), [rows]);
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
    // restart.variables intentionally omitted — the per-row loading check reads it fresh.
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

  return (
    <section>
      <header className="mb-4 flex items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-slate-900">Servicios QUERY</h2>
          <p className="mt-0.5 text-xs text-slate-500">
            Estado, logs y reinicio de los microservicios <code>kind=QUERY</code>
            ({services.length} {services.length === 1 ? "fila" : "filas"})
          </p>
        </div>
        <Legend />
      </header>

      <Table
        columns={columns}
        rows={services}
        rowKey={(m) => m.id}
        loading={false}
        empty="Aún no hay microservicios QUERY aprovisionados."
      />

      <LogsModal microservice={logsFor} onClose={() => setLogsFor(null)} />
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
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const pad = (n: number) => n.toString().padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(
    d.getHours(),
  )}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}