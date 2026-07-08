import { useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Drawer } from "@/components/ui/Drawer";
import { Input } from "@/components/ui/Input";
import { Table, type Column } from "@/components/ui/Table";
import { useToast } from "@/components/ui/Toast";
import { useMicroservices } from "@/hooks/useMicroservices";
import {
  useExecuteQuery,
  useQueriesForInstance,
  type QueryDefinition,
  type QueryExecutionResponse,
  type QueryParamValue,
} from "@/hooks/useQueries";
import { extractQueryParams } from "@/lib/queryParams";

/**
 * Consumer-facing catalog page. Lists every query the caller is
 * authorized to see (per-row role check, with ADMIN bypass),
 * optionally filtered by the backing microservice instance, and
 * lets them execute any of them against the chosen
 * `query-service-<instance>` container.
 *
 * <p>Three regions on a single page (NOT multi-step routing —
 * deep-link + browser back stay simple):
 * <ol>
 *   <li>Top — a `<select>` populated from
 *       {@link useMicroservices} filtered to `kind === "QUERY"`.
 *       The selected id is forwarded to
 *       {@link useQueriesForInstance} as the `microserviceId`
 *       filter (or `null` to see all instances).</li>
 *   <li>Middle — a `<Table>` of the returned queries with the
 *       uuid, type, publicEnd badge, and an "Ejecutar" action.
 *       Clicking the row opens the execute drawer.</li>
 *   <li>Drawer — auto-generated parameter form (one `<Input>`
 *       per `:placeholder` token from the SQL), an "Ejecutar"
 *       button, and a generic result table from the response.
 *       The Form primitive is NOT used here because it auto-
 *       renders Save semantics — wrong for "Execute".</li>
 * </ol>
 *
 * <p>Why "Todas las instancias" matters: a query with no
 * `MICROSERVICE_ID` (a "global" query) is visible when the
 * filter is null. Operators who add a query without picking an
 * instance still expect it to show up.
 */
export function QueriesCatalogPage() {
  const services = useMicroservices();
  const [selectedMicroserviceId, setSelectedMicroserviceId] = useState<string>("");
  const [drawerQuery, setDrawerQuery] = useState<QueryDefinition | null>(null);

  // null = "all instances", number = "this microservice only".
  // undefined means "user hasn't chosen yet" — we still want a
  // sane default so the table doesn't sit empty on first paint.
  const microserviceFilter: number | null =
    selectedMicroserviceId === ""
      ? null
      : Number.isFinite(Number(selectedMicroserviceId))
        ? Number(selectedMicroserviceId)
        : null;

  const queries = useQueriesForInstance(microserviceFilter);

  const queryInstances = useMemo(
    () =>
      (services.data ?? []).filter((m) => m.kind === "QUERY"),
    [services.data],
  );

  const columns = useMemo<Column<QueryDefinition>[]>(
    () => [
      {
        key: "uuid",
        header: "UUID",
        render: (q) => (
          <span className="font-mono text-xs text-slate-900">{q.uuid}</span>
        ),
      },
      {
        key: "type",
        header: "Tipo",
        render: (q) => q.type ?? "—",
      },
      {
        key: "publicEnd",
        header: "Público",
        render: (q) =>
          q.publicEnd ? (
            <span className="inline-flex rounded-full bg-emerald-50 px-2 py-0.5 text-[10px] font-medium text-emerald-700">
              Sí
            </span>
          ) : (
            <span className="text-xs text-slate-400">No</span>
          ),
      },
      {
        key: "instance",
        header: "Instancia",
        render: (q) => (q.microserviceId == null ? "Global" : `#${q.microserviceId}`),
      },
      {
        key: "actions",
        header: "",
        align: "right",
        render: (q) => (
          <Button
            size="sm"
            variant="secondary"
            onClick={() => setDrawerQuery(q)}
            data-testid={`execute-${q.idQuery}`}
          >
            Ejecutar
          </Button>
        ),
      },
    ],
    [],
  );

  return (
    <section>
      <header className="mb-4">
        <h1 className="text-xl font-semibold text-slate-900">Queries Catalog</h1>
        <p className="mt-0.5 text-xs text-slate-500">
          Ejecuta consultas del catálogo contra la instancia de <code>query-service</code> seleccionada.
        </p>
      </header>

      <div className="mb-4 flex items-center gap-3">
        <label
          htmlFor="microservice-picker"
          className="text-sm font-medium text-slate-700"
        >
          Instancia
        </label>
        <select
          id="microservice-picker"
          value={selectedMicroserviceId}
          onChange={(e) => setSelectedMicroserviceId(e.target.value)}
          data-testid="microservice-picker"
          className="rounded border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-sky-500 focus:outline-none focus:ring-1 focus:ring-sky-500"
        >
          <option value="">Todas las instancias</option>
          {queryInstances.map((m) => (
            <option key={m.id} value={String(m.id)}>
              {m.instanceName ?? m.serviceId} ({m.dialect ?? "?"})
            </option>
          ))}
        </select>
        {queryInstances.length === 0 && !services.isLoading ? (
          <span className="text-xs text-slate-500">
            Aún no hay microservicios QUERY aprovisionados.
          </span>
        ) : null}
      </div>

      <Table
        columns={columns}
        rows={queries.data ?? []}
        rowKey={(q) => q.idQuery}
        loading={queries.isLoading}
        empty={
          queries.isError
            ? "No se pudo cargar el catálogo. ¿sso-admin está UP?"
            : "No hay consultas visibles para esta instancia."
        }
      />

      <ExecuteDrawer
        query={drawerQuery}
        onClose={() => setDrawerQuery(null)}
      />
    </section>
  );
}

/* ====================== execute drawer ====================== */

interface ExecuteDrawerProps {
  query: QueryDefinition | null;
  onClose: () => void;
}

function ExecuteDrawer({ query, onClose }: ExecuteDrawerProps) {
  const services = useMicroservices();
  const execute = useExecuteQuery();
  const toast = useToast();

  // Per-drawer-open form state — reset every time the drawer
  // opens with a new query.
  const [paramValues, setParamValues] = useState<Record<string, QueryParamValue>>({});
  const [lastResult, setLastResult] = useState<QueryExecutionResponse | null>(null);

  // The instance bound to the query row. Falls back to "Todas"
  // when the query is global (no MICROSERVICE_ID).
  const instanceName = useMemo(() => {
    if (!query || query.microserviceId == null) return null;
    return (
      services.data?.find((m) => m.id === query.microserviceId)?.instanceName ?? null
    );
  }, [query, services.data]);

  const paramNames = useMemo(
    () => (query ? extractQueryParams(query.query) : []),
    [query],
  );

  // Reset local state every time we switch query (so a stale
  // result from a previous run doesn't leak into the new
  // drawer's body).
  const queryKey = query?.idQuery ?? null;
  useMemo(() => {
    setParamValues({});
    setLastResult(null);
    execute.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queryKey]);

  if (!query) return null;

  async function handleExecute() {
    if (!query) return;
    try {
      const result = await execute.mutateAsync({
        instanceName,
        body: {
          uuid: query.uuid,
          params: paramValues,
        },
      });
      setLastResult(result);
      if (result.length === 0) {
        toast.show("Consulta ejecutada — sin resultados", "success");
      } else {
        toast.show(`Consulta ejecutada — ${result.length} fila(s)`, "success");
      }
    } catch (err) {
      toast.show(`Error: ${(err as Error).message}`, "error");
    }
  }

  return (
    <Drawer
      open={!!query}
      onClose={onClose}
      title={`Ejecutar consulta ${query.uuid}`}
      description={
        instanceName
          ? `Instancia: query-service-${instanceName}`
          : "Instancia: query-service (canónica)"
      }
      width="lg"
    >
      <details className="mb-4 rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs">
        <summary className="cursor-pointer font-medium text-slate-700">SQL</summary>
        <pre className="mt-2 overflow-x-auto whitespace-pre-wrap break-all text-[11px] text-slate-700">
          {query.query}
        </pre>
      </details>

      {paramNames.length === 0 ? (
        <p className="mb-4 text-sm text-slate-500">
          Esta consulta no requiere parámetros.
        </p>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void handleExecute();
          }}
          className="mb-4 grid gap-3"
        >
          {paramNames.map((name) => (
            <Input
              key={name}
              label={name}
              value={String(paramValues[name] ?? "")}
              onChange={(e) =>
                setParamValues((prev) => ({
                  ...prev,
                  [name]: e.target.value,
                }))
              }
              data-testid={`param-${name}`}
            />
          ))}
          <div className="flex justify-end gap-2">
            <Button
              variant="secondary"
              type="button"
              onClick={onClose}
              data-testid="cancel-execute"
            >
              Cerrar
            </Button>
            <Button
              type="submit"
              loading={execute.isPending}
              data-testid="run-execute"
            >
              Ejecutar
            </Button>
          </div>
        </form>
      )}

      {paramNames.length === 0 ? (
        <div className="mb-4 flex justify-end gap-2">
          <Button
            variant="secondary"
            type="button"
            onClick={onClose}
            data-testid="cancel-execute"
          >
            Cerrar
          </Button>
          <Button
            type="button"
            loading={execute.isPending}
            onClick={() => void handleExecute()}
            data-testid="run-execute"
          >
            Ejecutar
          </Button>
        </div>
      ) : null}

      {execute.isError ? (
        <div
          role="alert"
          className="mb-4 rounded border border-red-300 bg-red-50 px-3 py-2 text-xs text-red-700"
          data-testid="execute-error"
        >
          {(execute.error as Error).message}
        </div>
      ) : null}

      {lastResult && lastResult.length > 0 ? (
        <ResultTable rows={lastResult} />
      ) : lastResult ? (
        <p
          className="rounded border border-dashed border-slate-300 bg-white p-4 text-center text-sm text-slate-500"
          data-testid="execute-empty"
        >
          Sin resultados.
        </p>
      ) : null}
    </Drawer>
  );
}

/* ====================== result table ====================== */

interface ResultTableProps {
  rows: QueryExecutionResponse;
}

/**
 * Generic result table. The query-service response is
 * {@code List<Map<String, Object>>} where the map keys are the
 * JDBC column labels (LinkedHashMap insertion order is
 * preserved server-side and Jackson preserves it on the wire).
 * We read the keys from the first row to derive the header.
 */
function ResultTable({ rows }: ResultTableProps) {
  const columns = useMemo<Column<Record<string, unknown>>[]>(() => {
    const first = rows[0];
    if (!first) return [];
    return Object.keys(first).map((key) => ({
      key,
      header: key,
      render: (row: Record<string, unknown>) => formatCell(row[key]),
    }));
  }, [rows]);

  return (
    <div className="mt-2">
      <p className="mb-2 text-xs text-slate-500">
        {rows.length} fila(s) devueltas
      </p>
      <Table
        columns={columns}
        rows={rows}
        rowKey={(row) => {
          // Best-effort stable key: prefer the first column
          // (the SELECT-list leftmost expression), fall back
          // to a JSON dump of the whole row when null/empty.
          const first = Object.values(row)[0];
          return first == null ? JSON.stringify(row) : String(first);
        }}
      />
    </div>
  );
}

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "object") {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
}