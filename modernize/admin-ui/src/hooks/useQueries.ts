import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { queriesApi } from "@/api/endpoints";
import type {
  QueryDefinition,
  QueryExecutionRequest,
  QueryExecutionResponse,
  QueryParamValue,
} from "@/api/types";

/**
 * React Query keys for the queries catalog. Naming follows the
 * `resourceKeys.{list, detail, ...}` convention used by the
 * other admin-ui hooks so `invalidateQueries` works the same
 * way.
 */
export const queryKeys = {
  all: ["queries"] as const,
  list: () => [...queryKeys.all, "list"] as const,
  forInstance: (microserviceId: number | null) =>
    [...queryKeys.all, "for-instance", microserviceId ?? "all"] as const,
};

/**
 * Fetches the catalog of queries the caller is authorized to
 * see, optionally filtered by microservice instance id. The
 * microserviceId is included in the queryKey so changing the
 * dropdown in the page auto-refetches.
 */
export function useQueriesForInstance(microserviceId: number | null) {
  return useQuery({
    queryKey: queryKeys.forInstance(microserviceId),
    queryFn: () => queriesApi.listForInstance(microserviceId),
  });
}

/**
 * Executes a query against the chosen query-service instance.
 * Returns TanStack Query's standard `useMutation` shape — call
 * `.mutateAsync({ instanceName, body })` from the execute
 * button.
 *
 * <p>On success the result is stashed under the queryKey for
 * the executor; nothing else is invalidated because
 * `POST /query` is side-effect-free on the catalog side.
 */
export function useExecuteQuery() {
  return useMutation<
    QueryExecutionResponse,
    Error,
    { instanceName: string | null; body: QueryExecutionRequest }
  >({
    mutationFn: ({ instanceName, body }) =>
      queriesApi.execute(instanceName, body),
  });
}

export type {
  QueryDefinition,
  QueryExecutionRequest,
  QueryExecutionResponse,
  QueryParamValue,
};

// Re-export so the page can invalidate manually if needed (e.g.
// when a role binding changes the catalog visibility).
export function useInvalidateQueries() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: queryKeys.all });
}