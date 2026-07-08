import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import { Table, type Column } from "@/components/ui/Table";
import { useToast } from "@/components/ui/Toast";
import {
  useAdminQueries,
  useBindQueryRole,
  useCreateQuery,
  useDeleteQuery,
  useInvalidateQueryAdminAndCatalog,
  useQueryRolesChecked,
  useUnbindQueryRole,
  useUpdateQuery,
} from "@/hooks/useAdminQueries";
import { useMicroservices } from "@/hooks/useMicroservices";
import type { QueryAdminResponse } from "@/api/types";
import { QueryFormDrawer } from "./QueryFormDrawer";
import type { QueryFormValues } from "@/schemas";

/**
 * Admin CRUD for the queries catalog. Mirrors the layout of
 * {@code EndpointsListPage}: list + create/edit drawer +
 * confirm-delete modal + a separate roles-binding modal.
 *
 * <p>Distinct from {@code QueriesCatalogPage} (the
 * consumer-facing execution page at {@code /admin/queries}):
 * this page manages the rows; the catalog page reads + executes
 * them. The two share the
 * {@code useInvalidateQueryAdminAndCatalog} hook so a role
 * binding here refreshes the consumer catalog too (per-row
 * auth changes visibility of queries for non-admin callers).
 *
 * <p>Two layout tricks worth flagging:
 * <ul>
 *   <li>The "Microservicio" column renders the
 *       microservice's {@code instanceName} or {@code serviceId}
 *       (not the numeric id) by cross-referencing
 *       {@link useMicroservices}. When the lookup returns
 *       nothing (id stale, row deleted), we fall back to
 *       {@code `#<id>`} so the table never blanks out.</li>
 *   <li>Roles column shows the bound count, not the names —
 *       long names would widen the column unpredictably; the
 *       "Roles" button opens the modal where the names
 *       surface.</li>
 * </ul>
 */
export function QueriesAdminPage() {
  const queries = useAdminQueries();
  const services = useMicroservices();
  const createQ = useCreateQuery();
  const updateQ = useUpdateQuery();
  const deleteQ = useDeleteQuery();
  const invalidateBoth = useInvalidateQueryAdminAndCatalog();
  const toast = useToast();

  const [editing, setEditing] = useState<QueryAdminResponse | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<QueryAdminResponse | null>(null);
  const [bindingRolesFor, setBindingRolesFor] =
    useState<QueryAdminResponse | null>(null);

  async function handleSubmit(values: QueryFormValues & { id?: number }) {
    const body = {
      uuid: values.uuid,
      query: values.query,
      type: values.type || null,
      publicEnd: values.publicEnd,
      captcha: values.captcha,
      detail: values.detail || null,
      action: values.action || null,
      style: values.style || null,
      microserviceId: values.microserviceId ?? null,
    };
    if (values.id) {
      await updateQ.mutateAsync({ id: values.id, ...body });
      toast.show("Query actualizado", "success");
    } else {
      await createQ.mutateAsync(body);
      toast.show("Query creado", "success");
    }
    setEditing(null);
    setCreating(false);
  }

  async function confirmDelete() {
    if (!deleting) return;
    await deleteQ.mutateAsync(deleting.id);
    toast.show("Query eliminado", "success");
    setDeleting(null);
    invalidateBoth();
  }

  function closeRolesModal() {
    setBindingRolesFor(null);
    invalidateBoth();
  }

  const columns: Column<QueryAdminResponse>[] = [
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
      key: "instance",
      header: "Instancia",
      render: (q) => {
        if (q.microserviceId == null) {
          return <span className="text-xs text-slate-400">Global</span>;
        }
        const m = services.data?.find((s) => s.id === q.microserviceId);
        return (
          <span className="text-xs">
            {m ? (
              <>
                <span className="font-mono">{m.instanceName ?? m.serviceId}</span>
                {m.dialect ? (
                  <span className="ml-1 text-slate-500">({m.dialect})</span>
                ) : null}
              </>
            ) : (
              <span className="text-slate-400">#{q.microserviceId}</span>
            )}
          </span>
        );
      },
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
      key: "roles",
      header: "Roles",
      align: "right",
      render: (q) => q.roleIds.length,
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (q) => (
        <div className="flex justify-end gap-2">
          <Button
            size="sm"
            variant="secondary"
            onClick={() => setBindingRolesFor(q)}
            data-testid={`bind-roles-${q.id}`}
          >
            Roles
          </Button>
          <Button
            size="sm"
            variant="secondary"
            onClick={() => setEditing(q)}
            data-testid={`edit-${q.id}`}
          >
            Editar
          </Button>
          <Button
            size="sm"
            variant="danger"
            onClick={() => setDeleting(q)}
            data-testid={`delete-${q.id}`}
          >
            Eliminar
          </Button>
        </div>
      ),
    },
  ];

  return (
    <section>
      <header className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold text-slate-900">
            Queries Catalog (admin)
          </h1>
          <p className="mt-0.5 text-xs text-slate-500">
            Crea y vincula queries a microservicios QUERY. La página
            <code className="mx-1">Queries Catalog</code>es la cara de
            consumidor.
          </p>
        </div>
        <Button onClick={() => setCreating(true)} data-testid="new-query">
          + Nuevo query
        </Button>
      </header>

      <Table
        columns={columns}
        rows={queries.data ?? []}
        rowKey={(q) => q.id}
        loading={queries.isLoading}
        empty={
          queries.isError
            ? "No se pudo cargar el catálogo. ¿sso-admin está UP?"
            : "Aún no hay queries."
        }
      />

      <QueryFormDrawer
        open={creating || editing !== null}
        query={editing}
        onClose={() => {
          setCreating(false);
          setEditing(null);
        }}
        onSubmit={handleSubmit}
      />

      <Modal
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        title="Eliminar query"
        description={
          deleting ? `¿Seguro que quieres eliminar "${deleting.uuid}"?` : ""
        }
        footer={
          <>
            <Button variant="secondary" onClick={() => setDeleting(null)}>
              Cancelar
            </Button>
            <Button
              variant="danger"
              loading={deleteQ.isPending}
              onClick={() => void confirmDelete()}
            >
              Eliminar
            </Button>
          </>
        }
      >
        <p className="text-sm text-slate-600">
          Se eliminarán los bindings a roles asociados.
        </p>
      </Modal>

      {bindingRolesFor ? (
        <RolesBindingModal
          query={bindingRolesFor}
          onClose={closeRolesModal}
        />
      ) : null}
    </section>
  );
}

/* ====================== roles binding modal ====================== */

interface RolesBindingModalProps {
  query: QueryAdminResponse;
  onClose: () => void;
}

/**
 * Two-button-per-row roles modal: each role gets a
 * "Vincular" / "Desvincular" button. We avoid a multi-select
 * checkbox list for two reasons:
 * <ol>
 *   <li>The roles that are unbound are a future-suggestion
 *       we don't want to encourage — the user should opt-IN
 *       a role, not opt-out from a wall of checkboxes.</li>
 *   <li>Each toggle is one HTTP round-trip; pairing them
 *       makes the consequence of each click explicit and
 *       trivially auditable in the role repository test
 *       suite.</li>
 * </ol>
 */
function RolesBindingModal({ query, onClose }: RolesBindingModalProps) {
  const roles = useQueryRolesChecked(query.id);
  const bind = useBindQueryRole();
  const unbind = useUnbindQueryRole();

  async function toggle(roleId: number, currentlyBound: boolean) {
    if (currentlyBound) {
      await unbind.mutateAsync({ id: query.id, roleId });
    } else {
      await bind.mutateAsync({ id: query.id, roleId });
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={`Roles para ${query.uuid}`}
      description={`Vincula o desvincula roles en este query. Los cambios son 1 round-trip por acción.`}
      footer={
        <Button variant="secondary" onClick={onClose}>
          Cerrar
        </Button>
      }
    >
      <ul className="divide-y divide-slate-200" data-testid="query-roles-list">
        {(roles.data ?? []).map((r) => (
          <li
            key={r.roleId}
            className="flex items-center justify-between py-2 text-sm"
          >
            <span className="font-medium text-slate-800">{r.name}</span>
            <Button
              size="sm"
              variant={r.checked ? "secondary" : "primary"}
              disabled={bind.isPending || unbind.isPending}
              loading={bind.isPending || unbind.isPending}
              onClick={() => void toggle(r.roleId, r.checked)}
              data-testid={`role-toggle-${r.roleId}`}
            >
              {r.checked ? "Desvincular" : "Vincular"}
            </Button>
          </li>
        ))}
        {roles.data && roles.data.length === 0 ? (
          <li className="py-2 text-sm text-slate-500">No hay roles creados.</li>
        ) : null}
      </ul>
    </Modal>
  );
}
