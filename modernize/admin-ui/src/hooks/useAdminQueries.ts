import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { queryAdminApi } from "@/api/endpoints";
import type { QueryAdminRequest, QueryAdminResponse, QueryRoleChecked } from "@/api/types";

/**
 * TanStack Query keys for the admin CRUD surface. Distinct
 * from the consumer-side {@link useQueriesForInstance}
 * keys so a mutation on one side never invalidates the other
 * — that would cause spurious refetches while a user is in
 * the middle of executing a query.
 */
export const queryAdminKeys = {
  all: ["queryAdmin"] as const,
  list: () => [...queryAdminKeys.all, "list"] as const,
  rolesChecked: (id: number) => [...queryAdminKeys.all, "rolesChecked", id] as const,
};

export function useAdminQueries() {
  return useQuery({
    queryKey: queryAdminKeys.list(),
    queryFn: () => queryAdminApi.list(),
  });
}

export function useQueryRolesChecked(id: number | null) {
  return useQuery({
    queryKey: queryAdminKeys.rolesChecked(id ?? 0),
    queryFn: () => queryAdminApi.getRolesChecked(id!),
    enabled: id != null,
  });
}

export function useCreateQuery() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: QueryAdminRequest) => queryAdminApi.create(body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryAdminKeys.list() });
    },
  });
}

export function useUpdateQuery() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: QueryAdminRequest) => queryAdminApi.update(body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryAdminKeys.list() });
    },
  });
}

export function useDeleteQuery() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => queryAdminApi.delete(id),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryAdminKeys.list() });
    },
  });
}

export function useBindQueryRole() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, roleId }: { id: number; roleId: number }) =>
      queryAdminApi.bindRole(id, roleId),
    onSuccess: (_d, vars) => {
      void qc.invalidateQueries({
        queryKey: queryAdminKeys.rolesChecked(vars.id),
      });
    },
  });
}

export function useUnbindQueryRole() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, roleId }: { id: number; roleId: number }) =>
      queryAdminApi.unbindRole(id, roleId),
    onSuccess: (_d, vars) => {
      void qc.invalidateQueries({
        queryKey: queryAdminKeys.rolesChecked(vars.id),
      });
    },
  });
}

export type { QueryAdminRequest, QueryAdminResponse, QueryRoleChecked };

/**
 * Manually invalidate both the admin list and the consumer
 * catalog after a role-binding change. The consumer catalog
 * filters by the caller's role authorization, so adding a
 * role for a new query requires the consumer view to refetch
 * too. Bounded to the use-case the admin page needs; the
 * page calls this after closing its role modal.
 */
export function useInvalidateQueryAdminAndCatalog() {
  const qc = useQueryClient();
  return () => {
    void qc.invalidateQueries({ queryKey: queryAdminKeys.all });
    void qc.invalidateQueries({ queryKey: ["queries"] });
  };
}
