import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ToastProvider } from "@/components/ui/Toast";
import { MicroservicesListPage } from "./MicroservicesListPage";
import { apiClient } from "@/api/client";
import type { MicroserviceResponse } from "@/api/types";

/**
 * The microservices page is REST-only after the QUERY split:
 * QUERY services (and their drawer) live on the Query Services
 * page. These tests assert the REST create flow and that QUERY
 * rows never leak into this table.
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

function mkMs(over: Partial<MicroserviceResponse> = {}): MicroserviceResponse {
  return {
    id: 1,
    serviceId: "orders",
    description: "orders api",
    requestUri: "/api/orders/**",
    targetUriPath: "/v1",
    targetUrlHost: "orders.internal",
    targetUrlPort: "8080",
    createdDate: "2026-06-26T00:00:00Z",
    kind: "REST",
    dialect: null,
    jdbcUrl: null,
    dbUsername: null,
    dbPassword: null,
    poolSize: null,
    instanceName: null,
    ...over,
  };
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("MicroservicesListPage — REST-only", () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    apiClient.setAccessTokenGetter(() => "test-jwt");
    apiClient.setAuthFailureHandler(() => {});
    fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("POSTs kind=REST to /sso-admin/microservice/save with the routing fields", async () => {
    fetchSpy.mockResolvedValueOnce(jsonResponse([])); // initial empty list
    fetchSpy.mockResolvedValue(jsonResponse(mkMs({ id: 1, serviceId: "diag-svc" }))); // save + refetch

    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole("button", { name: /Nuevo microservicio/i }));

    // REST-only drawer: no kind selector.
    expect(screen.queryByRole("radio", { name: /Query service/i })).not.toBeInTheDocument();

    await user.type(screen.getByLabelText(/Service ID/i), "diag-svc");
    await user.type(screen.getByLabelText(/Request URI/i), "/api/diag/**");
    await user.type(screen.getByLabelText(/Target host/i), "diag.internal");
    await user.type(screen.getByLabelText(/Target port/i), "9000");
    await user.type(screen.getByLabelText(/Target path/i), "/v1");

    await user.click(screen.getByRole("button", { name: /Crear/i }));

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
      ([url]) => typeof url === "string" && url.includes("/sso-admin/microservice/save"),
    );
    const body = JSON.parse((saveCall![1] as RequestInit).body as string);
    expect(body.kind).toBe("REST");
    expect(body.serviceId).toBe("diag-svc");
    expect(body.targetUrlHost).toBe("diag.internal");
    expect(body.targetUrlPort).toBe("9000");
  });

  it("lists REST rows and filters out QUERY services", async () => {
    const rest = mkMs({ id: 1, serviceId: "orders" });
    const query = mkMs({
      id: 2,
      serviceId: "q-oracle",
      kind: "QUERY",
      dialect: "oracle",
      jdbcUrl: "jdbc:oracle:thin:@db:1521/ORCLPDB1",
      instanceName: "oracle-dev",
    });
    fetchSpy.mockResolvedValue(jsonResponse([rest, query]));

    renderPage();
    expect(await screen.findByText("orders")).toBeInTheDocument();
    expect(screen.queryByText("q-oracle")).not.toBeInTheDocument();
  });
});
