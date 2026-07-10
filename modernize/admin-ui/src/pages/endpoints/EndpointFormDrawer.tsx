import { useMemo } from "react";
import { Drawer } from "@/components/ui/Drawer";
import { Input } from "@/components/ui/Input";
import { Form, zodFieldErrors } from "@/components/forms/Form";
import { Tabs, type TabItem } from "@/components/ui/Tabs";
import { BindingTab, type BulkAction } from "@/components/ui/BindingTab";
import { endpointFormSchema, type EndpointFormValues } from "@/schemas";
import type { EndpointResponse, EndpointRoleChecked } from "@/api/types";
import {
  useBindEndpointRole,
  useEndpointRolesChecked,
  useUnbindEndpointRole,
} from "@/hooks/useEndpoints";

interface Props {
  open: boolean;
  endpoint: EndpointResponse | null;
  onClose: () => void;
  onSubmit: (values: EndpointFormValues & { id?: number }) => Promise<void>;
}

const METHODS = ["GET", "POST", "PUT", "DELETE", "PATCH"] as const;

/**
 * Drawer for create + edit of an Endpoint. Hosts 2 tabs:
 * <ol>
 *   <li><b>General</b> — method/path/description/#params, the
 *       only tab that submits a CRUD payload.</li>
 *   <li><b>Roles</b> — checked-listing of every role, one
 *       Vincular/Desvincular toggle per row ({@code role_endpoint}
 *       — this is what {@code SsoAdminAccessManager} actually
 *       checks per request, unlike {@code role_route} which only
 *       controls menu visibility). Available only in edit mode —
 *       same shape as {@code RouteFormDrawer}'s Roles tab.</li>
 * </ol>
 */
export function EndpointFormDrawer({ open, endpoint, onClose, onSubmit }: Props) {
  const initialValues: EndpointFormValues = useMemo(
    () => ({
      method: (endpoint?.method as EndpointFormValues["method"]) ?? "GET",
      path: endpoint?.path ?? "",
      description: endpoint?.description ?? "",
      numberParams: endpoint?.numberParams ?? 0,
    }),
    [endpoint],
  );

  const tabs: TabItem[] = [
    {
      key: "general",
      label: "General",
      content: (
        <GeneralTab
          endpoint={endpoint}
          initialValues={initialValues}
          onSubmit={onSubmit}
          onClose={onClose}
        />
      ),
    },
    {
      key: "roles",
      label: "Roles",
      content: endpoint ? <RolesTab endpointId={endpoint.id} /> : <DisabledTabHint />,
    },
  ];

  return (
    <Drawer
      open={open}
      onClose={onClose}
      title={endpoint ? `Editar endpoint: ${endpoint.method} ${endpoint.path}` : "Nuevo endpoint"}
      description="Define un método y patrón de URI. Vincula los roles que pueden invocarlo en la pestaña Roles."
      footer={null}
      width="lg"
    >
      <Tabs tabs={tabs} ariaLabel="Secciones del formulario de Endpoint" />
    </Drawer>
  );
}

/* ====================== General tab ====================== */

interface GeneralTabProps {
  endpoint: EndpointResponse | null;
  initialValues: EndpointFormValues;
  onSubmit: (values: EndpointFormValues & { id?: number }) => Promise<void>;
  onClose: () => void;
}

function GeneralTab({ endpoint, initialValues, onSubmit, onClose }: GeneralTabProps) {
  return (
    <Form<EndpointFormValues>
      initialValues={initialValues}
      validate={(values) => {
        const result = endpointFormSchema.safeParse(values);
        if (result.success) return {};
        return zodFieldErrors(result.error);
      }}
      onSubmit={async (values) => {
        await onSubmit(endpoint ? { id: endpoint.id, ...values } : values);
      }}
      onCancel={onClose}
      submitLabel={endpoint ? "Guardar cambios" : "Crear"}
    >
      {({ values, setField, errors }) => (
        <>
          <div className="grid grid-cols-[7rem_1fr_6rem] gap-3">
            <label className="block text-sm">
              <span className="mb-1 block font-medium text-slate-700">
                Método<span className="ml-0.5 text-red-600">*</span>
              </span>
              <select
                value={values.method}
                onChange={(e) =>
                  setField("method", e.target.value as EndpointFormValues["method"])
                }
                className="w-full rounded border border-slate-300 bg-white px-3 py-2 text-sm outline-none focus:border-sky-500 focus:ring-1 focus:ring-sky-500"
              >
                {METHODS.map((m) => (
                  <option key={m} value={m}>
                    {m}
                  </option>
                ))}
              </select>
              {errors.method ? (
                <p role="alert" className="mt-1 text-xs text-red-600">
                  {errors.method}
                </p>
              ) : null}
            </label>
            <Input
              label="Path"
              required
              value={values.path}
              onChange={(e) => setField("path", e.target.value)}
              error={errors.path}
              hint="Soporta * y ** como wildcards, y {var} para path variables"
            />
            <Input
              label="# params"
              required
              type="number"
              min={0}
              max={20}
              value={values.numberParams}
              onChange={(e) => setField("numberParams", Number(e.target.value))}
              error={errors.numberParams}
            />
          </div>
          <div className="h-3" />
          <Input
            label="Descripción"
            value={values.description}
            onChange={(e) => setField("description", e.target.value)}
            error={errors.description}
          />
        </>
      )}
    </Form>
  );
}

function DisabledTabHint() {
  return (
    <p className="text-sm text-slate-500">
      Guarda el endpoint primero para habilitar la gestión de roles.
    </p>
  );
}

/* ====================== Roles tab ====================== */

function RolesTab({ endpointId }: { endpointId: number }) {
  const roles = useEndpointRolesChecked(endpointId);
  const bind = useBindEndpointRole();
  const unbind = useUnbindEndpointRole();
  const pending = bind.isPending || unbind.isPending;

  const bulkAction: BulkAction = {
    bindLabel: "Vincular visibles",
    unbindLabel: "Desvincular visibles",
    testIdPrefix: "role-bulk",
    onApply: (rowIds, action) => {
      const mutator = action === "bind" ? bind : unbind;
      void Promise.all(
        rowIds.map((roleId) => mutator.mutateAsync({ id: endpointId, roleId })),
      );
    },
  };

  return (
    <BindingTab<EndpointRoleChecked>
      entityId={endpointId}
      listTestIdPrefix="endpoint-bindings"
      data={roles.data}
      isLoading={roles.isLoading}
      isPending={pending}
      emptyText="No hay roles creados."
      toggleIdPrefix="role-toggle"
      searchPlaceholder="Buscar rol…"
      getRowLabel={(r) => r.name}
      getRowId={(r) => r.roleId}
      getRowChecked={(r) => r.checked}
      onToggle={(roleId, checked) => {
        if (checked) {
          void unbind.mutateAsync({ id: endpointId, roleId });
        } else {
          void bind.mutateAsync({ id: endpointId, roleId });
        }
      }}
      bulkAction={bulkAction}
      renderRow={(r) => r.name}
    />
  );
}
