import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ToastProvider } from "@/components/ui/Toast";
import { QueriesCatalogPage } from "./QueriesCatalogPage";
import type {
  MicroserviceResponse,
  QueryDefinition,
} from "@/api/types";

/**
 * Renders the page with a fresh QueryClient (staleTime 0, retry
 * off, no refetch-on-focus) and a MemoryRouter. The page itself
 * does not consume router primitives but the surrounding
 * <App/> uses NavLinks; matching the convention from
 * QueryServicesListPage.test.tsx keeps setup consistent.
 *
 * We stub `fetch` so the hooks (useMicroservices,
 * useQueriesForInstance) hit in-process responses. The execute
 * hook goes through the same `fetch` and we assert on its URL
 * to verify the apiClient `base: ""` override works as
 * designed.
 *
 * Routing: useMicroservices and useQueriesForInstance fire in
 * parallel on mount — TanStack Query does not guarantee an
 * order. We dispatch by URL fragment instead of stacking
 * mockResolvedValueOnce calls so the tests don't depend on
 * which fetch lands first.
 */
function renderPage() {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { staleTime: 0, gcTime: 0, retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <MemoryRouter>
          <QueriesCatalogPage />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

function mkMs(over: Partial<MicroserviceResponse> = {}): MicroserviceResponse {
  return {
    id: 1,
    serviceId: "query-oracle-dev",
    description: "dev oracle",
    requestUri: "/api/queries/**",
    targetUriPath: "",
    targetUrlHost: "",
    targetUrlPort: "",
    createdDate: "2026-06-01T00:00:00Z",
    kind: "QUERY",
    dialect: "oracle",
    jdbcUrl: "jdbc:oracle:thin:@db:1521/ORCLPDB1",
    dbUsername: "query",
    dbPassword: null,
    poolSize: 10,
    instanceName: "oracle-dev",
    ...over,
  };
}

function mkQuery(over: Partial<QueryDefinition> = {}): QueryDefinition {
  return {
    idQuery: 1,
    uuid: "q-1",
    query: "SELECT * FROM t WHERE id = :id",
    type: "select",
    publicEnd: true,
    captcha: false,
    detail: null,
    action: null,
    style: null,
    microserviceId: 1,
    ...over,
  };
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function errorResponse(status: number, code: string, message: string) {
  return new Response(
    JSON.stringify({ code, message, timestamp: "" }),
    { status, headers: { "Content-Type": "application/json" } },
  );
}

function findFetchCall(fetchSpy: ReturnType<typeof vi.fn>, urlFragment: string) {
  return fetchSpy.mock.calls.find(([url]) =>
    typeof url === "string" && url.includes(urlFragment),
  );
}

/**
 * Build a fetch spy that responds per-URL. The execute call
 * (POST /query-service-<instance>/query) returns
 * `executeResult`; the catalog + microservice list calls return
 * the inputs. Anything else rejects so a regression in the
 * routing surfaces loudly.
 */
function buildFetchSpy(opts: {
  microservices: MicroserviceResponse[];
  queries: QueryDefinition[];
  executeResult?: { status: number; body: unknown; isError?: boolean };
}) {
  const fetchSpy = vi.fn((url: string | URL | Request) => {
    const u = typeof url === "string" ? url : url.toString();
    if (u.includes("/sso-admin/microservice/getMicroservices")) {
      return Promise.resolve(jsonResponse(opts.microservices));
    }
    if (u.includes("/sso-admin/myQueries")) {
      return Promise.resolve(jsonResponse(opts.queries));
    }
    if (u.includes("/query")) {
      // execute
      if (opts.executeResult?.isError) {
        return Promise.resolve(
          errorResponse(opts.executeResult.status, "BAD", opts.executeResult.body as string),
        );
      }
      return Promise.resolve(
        jsonResponse(opts.executeResult?.body ?? []),
      );
    }
    return Promise.reject(new Error(`Unexpected fetch URL: ${u}`));
  });
  return fetchSpy;
}

describe("QueriesCatalogPage", () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("populates the microservice dropdown with QUERY-kind rows only", async () => {
    const restRow = mkMs({
      id: 99,
      serviceId: "orders",
      kind: "REST",
      requestUri: "/api/orders/**",
      targetUriPath: "/v1",
      targetUrlHost: "orders.internal",
      targetUrlPort: "8080",
      instanceName: null,
    });
    const queryRow1 = mkMs({ id: 1, serviceId: "q-oracle", instanceName: "oracle-dev" });
    const queryRow2 = mkMs({ id: 2, serviceId: "q-pg", instanceName: "pg", dialect: "postgres" });
    const spy = buildFetchSpy({ microservices: [restRow, queryRow1, queryRow2], queries: [] });
    vi.stubGlobal("fetch", spy);

    renderPage();
    // Wait for the QUERY rows to land in the dropdown (the
    // <select> is in the DOM before the query resolves, so
    // findByTestId alone is not enough).
    const oracleOption = await screen.findByRole("option", { name: /oracle-dev/i });
    expect(oracleOption).toBeInTheDocument();
    const pgOption = await screen.findByRole("option", { name: /pg/i });
    expect(pgOption).toBeInTheDocument();
    const picker = screen.getByTestId("microservice-picker") as HTMLSelectElement;
    // Three options: Todas las instancias + 2 QUERY rows
    expect(picker.options.length).toBe(3);
    // REST row must not be present
    const labels = Array.from(picker.options).map((o) => o.text);
    expect(labels.some((l) => l && l.includes("orders"))).toBe(false);
    expect(labels.some((l) => l && l.includes("oracle-dev"))).toBe(true);
    expect(labels.some((l) => l && l.includes("pg"))).toBe(true);
  });

  it("shows the empty-instances hint when no QUERY rows exist", async () => {
    const restRow = mkMs({ id: 99, kind: "REST", instanceName: null });
    const spy = buildFetchSpy({ microservices: [restRow], queries: [] });
    vi.stubGlobal("fetch", spy);

    renderPage();
    expect(
      await screen.findByText(/Aún no hay microservicios QUERY aprovisionados/i),
    ).toBeInTheDocument();
  });

  it("renders one row per query returned by /myQueries", async () => {
    const ms = mkMs({ id: 1, instanceName: "oracle-dev" });
    const q1 = mkQuery({ idQuery: 1, uuid: "q-1" });
    const q2 = mkQuery({ idQuery: 2, uuid: "q-2", publicEnd: false, type: "report" });
    const spy = buildFetchSpy({ microservices: [ms], queries: [q1, q2] });
    vi.stubGlobal("fetch", spy);

    renderPage();
    expect(await screen.findByText("q-1")).toBeInTheDocument();
    expect(screen.getByText("q-2")).toBeInTheDocument();
    expect(screen.getByText("select")).toBeInTheDocument();
    expect(screen.getByText("report")).toBeInTheDocument();
    // Both rows expose an "Ejecutar" action
    expect(screen.getByTestId("execute-1")).toBeInTheDocument();
    expect(screen.getByTestId("execute-2")).toBeInTheDocument();
  });

  it("selecting a microservice triggers a /myQueries?microserviceId= call", async () => {
    const ms1 = mkMs({ id: 1, instanceName: "oracle-dev" });
    const ms2 = mkMs({
      id: 2,
      serviceId: "q-pg",
      instanceName: "pg",
      dialect: "postgres",
    });
    const spy = buildFetchSpy({ microservices: [ms1, ms2], queries: [] });
    vi.stubGlobal("fetch", spy);

    renderPage();
    // Wait for the dropdown to populate before trying to select
    // an option (option "2" doesn't exist until /getMicroservices
    // resolves).
    await screen.findByRole("option", { name: /pg/i });
    const picker = screen.getByTestId("microservice-picker") as HTMLSelectElement;
    await userEvent.selectOptions(picker, "2");

    await waitFor(() =>
      expect(
        findFetchCall(spy, "/sso-admin/myQueries?microserviceId=2"),
      ).toBeDefined(),
    );
  });

  it("clicking 'Ejecutar' opens the drawer with the SQL and a param input", async () => {
    const ms = mkMs({ id: 1, instanceName: "oracle-dev" });
    const q = mkQuery({
      idQuery: 7,
      uuid: "q-param",
      query: "SELECT * FROM t WHERE id = :id AND name = :name",
    });
    const spy = buildFetchSpy({ microservices: [ms], queries: [q] });
    vi.stubGlobal("fetch", spy);

    renderPage();
    await userEvent.click(await screen.findByTestId("execute-7"));

    // Drawer header
    expect(
      await screen.findByRole("dialog", { name: /Ejecutar consulta q-param/i }),
    ).toBeInTheDocument();
    // Param inputs auto-generated from the SQL
    expect(screen.getByTestId("param-id")).toBeInTheDocument();
    expect(screen.getByTestId("param-name")).toBeInTheDocument();
    // Execute + cancel buttons
    expect(screen.getByTestId("run-execute")).toBeInTheDocument();
    expect(screen.getByTestId("cancel-execute")).toBeInTheDocument();
  });

  it("submitting Execute calls POST /query-service-<instance>/query and renders the result table", async () => {
    const ms = mkMs({ id: 1, instanceName: "oracle-dev" });
    const q = mkQuery({
      idQuery: 7,
      uuid: "q-param",
      query: "SELECT id, name FROM t WHERE id = :id",
    });
    const execResult = [{ id: 1, name: "alice" }, { id: 2, name: "bob" }];
    const spy = buildFetchSpy({
      microservices: [ms],
      queries: [q],
      executeResult: { status: 200, body: execResult },
    });
    vi.stubGlobal("fetch", spy);

    renderPage();
    await userEvent.click(await screen.findByTestId("execute-7"));
    await userEvent.type(screen.getByTestId("param-id"), "1");
    await userEvent.click(screen.getByTestId("run-execute"));

    // The execute URL is /query-service-oracle-dev/query (NO /api
    // prefix) — the apiClient `base: ""` override is what produces
    // this shape.
    await waitFor(() =>
      expect(
        findFetchCall(spy, "/query-service-oracle-dev/query"),
      ).toBeDefined(),
    );
    // Request body carries the uuid and the param value the user typed.
    const execCall = findFetchCall(spy, "/query-service-oracle-dev/query");
    expect(execCall).toBeDefined();
    const bodyArg = JSON.parse((execCall![1] as RequestInit).body as string);
    expect(bodyArg).toEqual({ uuid: "q-param", params: { id: "1" } });

    // Result table renders with headers from Object.keys(row[0])
    expect(await screen.findByText("2 fila(s) devueltas")).toBeInTheDocument();
    expect(screen.getByText("alice")).toBeInTheDocument();
    expect(screen.getByText("bob")).toBeInTheDocument();
  });

  it("uses the canonical /query-service/query path when the query is global (no microserviceId)", async () => {
    // No microservices at all in the dropdown, but the catalog
    // returns a global row. The drawer should fall back to the
    // legacy single-instance service id.
    const globalQ = mkQuery({
      idQuery: 42,
      uuid: "q-global",
      query: "SELECT 1",
      microserviceId: null,
    });
    const spy = buildFetchSpy({
      microservices: [],
      queries: [globalQ],
      executeResult: { status: 200, body: [{ "?column?": 1 }] },
    });
    vi.stubGlobal("fetch", spy);

    renderPage();
    await userEvent.click(await screen.findByTestId("execute-42"));
    await userEvent.click(screen.getByTestId("run-execute"));

    await waitFor(() =>
      expect(findFetchCall(spy, "/query-service/query")).toBeDefined(),
    );
    // Drawer description should advertise the canonical instance
    expect(
      screen.getByText(/Instancia: query-service \(canónica\)/i),
    ).toBeInTheDocument();
  });

  it("shows the empty state in the drawer when the query returns no rows", async () => {
    const ms = mkMs({ id: 1, instanceName: "oracle-dev" });
    const q = mkQuery({ idQuery: 9, uuid: "q-empty", query: "SELECT 1 WHERE 1=0" });
    const spy = buildFetchSpy({
      microservices: [ms],
      queries: [q],
      executeResult: { status: 200, body: [] },
    });
    vi.stubGlobal("fetch", spy);

    renderPage();
    await userEvent.click(await screen.findByTestId("execute-9"));
    await userEvent.click(screen.getByTestId("run-execute"));

    expect(
      await screen.findByTestId("execute-empty"),
    ).toHaveTextContent(/Sin resultados/i);
  });

  it("renders an inline error when the execute call fails with an ApiError", async () => {
    const ms = mkMs({ id: 1, instanceName: "oracle-dev" });
    const q = mkQuery({ idQuery: 11, uuid: "q-bad", query: "SELECT 1" });
    const spy = buildFetchSpy({
      microservices: [ms],
      queries: [q],
      executeResult: {
        status: 400,
        body: "syntax error at end of input",
        isError: true,
      },
    });
    vi.stubGlobal("fetch", spy);

    renderPage();
    await userEvent.click(await screen.findByTestId("execute-11"));
    await userEvent.click(screen.getByTestId("run-execute"));

    const errEl = await screen.findByTestId("execute-error");
    expect(errEl).toHaveTextContent(/syntax error at end of input/i);
  });
});
