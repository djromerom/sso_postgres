import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ToastProvider } from "@/components/ui/Toast";
import { MicroservicesListPage } from "./MicroservicesListPage";
import { apiClient } from "@/api/client";

/**
 * End-to-end repro of the user's bug report: navigate to
 * /admin/microservices, click "+ Nuevo", pick "Query service",
 * fill the QUERY-only fields, click "Crear", and inspect
 * what's actually PUT on the wire.
 *
 * If this test passes, the frontend is correct and the bug
 * is somewhere else (cached browser dist, backend service,
 * etc.). If it fails, the bug is reproducible in code.
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
        <MicroservicesListPage />
      </ToastProvider>
    </QueryClientProvider>,
  );
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("MicroservicesListPage — kind=QUERY end-to-end", () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    // Wire the apiClient to a real-ish auth token so it doesn't
    // try to refresh on 401.
    apiClient.setAccessTokenGetter(() => "test-jwt");
    apiClient.setAuthFailureHandler(() => {});

    fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("POSTs kind=QUERY to /sso-admin/microservice/save when the user picks Query service", async () => {
    // Initial list call (empty)
    fetchSpy.mockResolvedValueOnce(jsonResponse([]));
    // The save call
    const createdRow = {
      id: 1,
      serviceId: "diag-svc",
      description: "",
      requestUri: "/api/diag/**",
      targetUriPath: "",
      targetUrlHost: "",
      targetUrlPort: "",
      createdDate: "2026-06-26T00:00:00Z",
      kind: "QUERY",
      dialect: "postgres",
      jdbcUrl: "jdbc:postgresql://x/y",
      dbUsername: "u",
      dbPassword: null,
      poolSize: 10,
      instanceName: "diag",
    };
    fetchSpy.mockResolvedValueOnce(jsonResponse(createdRow));

    const user = userEvent.setup();
    renderPage();

    // Open the create drawer
    await user.click(screen.getByRole("button", { name: /Nuevo microservicio/i }));

    // Pick Query service
    await user.click(screen.getByRole("radio", { name: /Query service/i }));

    // Required REST-base fields
    await user.type(screen.getByLabelText(/Service ID/i), "diag-svc");
    await user.type(screen.getByLabelText(/Request URI/i), "/api/diag/**");

    // QUERY-only fields
    await user.selectOptions(screen.getByLabelText(/Dialecto/i), "postgres");
    await user.type(screen.getByLabelText(/JDBC URL/i), "jdbc:postgresql://x/y");
    await user.type(screen.getByLabelText(/DB username/i), "u");
    await user.type(screen.getByLabelText(/Instance name/i), "diag");

    // Submit
    await user.click(screen.getByRole("button", { name: /Crear/i }));

    // Wait for the save POST
    await waitFor(() => {
      const saveCall = fetchSpy.mock.calls.find(
        ([url, init]) =>
          typeof url === "string" &&
          url.includes("/sso-admin/microservice/save") &&
          (init as RequestInit | undefined)?.method === "POST",
      );
      expect(saveCall).toBeDefined();
    });

    const saveCall = fetchSpy.mock.calls.find(
      ([url]) =>
        typeof url === "string" && url.includes("/sso-admin/microservice/save"),
    );
    const init = saveCall![1] as RequestInit;
    const body = JSON.parse(init.body as string);

    // The smoking gun
    expect(body.kind).toBe("QUERY");
    // Belt-and-suspenders
    expect(body.serviceId).toBe("diag-svc");
    expect(body.dialect).toBe("postgres");
    expect(body.instanceName).toBe("diag");

    // Echo the wire payload to the test log so the user can
    // confirm what's actually being sent on the wire.
    // eslint-disable-next-line no-console
    console.log("\n>>> wire payload:\n" + JSON.stringify(body, null, 2));
  });

  it("renders inline QUERY operations for provisioned microservices", async () => {
    const queryRow = {
      id: 7,
      serviceId: "query-oracle-dev",
      description: "dev oracle",
      requestUri: "/api/queries/**",
      targetUriPath: "",
      targetUrlHost: "",
      targetUrlPort: "",
      createdDate: "2026-06-26T00:00:00Z",
      kind: "QUERY",
      dialect: "oracle",
      jdbcUrl: "jdbc:oracle:thin:@db:1521/ORCLPDB1",
      dbUsername: "query",
      dbPassword: null,
      poolSize: 10,
      instanceName: "oracle-dev",
    };
    fetchSpy.mockResolvedValueOnce(jsonResponse([queryRow]));
    fetchSpy.mockResolvedValueOnce(
      jsonResponse({
        state: "running",
        rawState: "running",
        containerId: "abc123",
        startedAt: "2026-06-24T10:00:00.000Z",
        fullName: "query-service-oracle-dev",
      }),
    );

    renderPage();

    expect(await screen.findByText(/Servicios QUERY/i)).toBeInTheDocument();
    expect(await screen.findByTestId("status-cell-7")).toHaveTextContent(/UP/i);
    expect(screen.getByTestId("view-logs-7")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Reiniciar/i })).toBeInTheDocument();
  });
});
