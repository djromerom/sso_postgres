import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Modal } from "@/components/ui/Modal";
import { Table, type Column } from "@/components/ui/Table";
import { useToast } from "@/components/ui/Toast";
import {
  useCreateMicroservice,
  useDeleteMicroservice,
  useMicroservices,
  useUpdateMicroservice,
} from "@/hooks/useMicroservices";
import type { MicroserviceResponse, MicroserviceKind } from "@/api/types";
import { MicroserviceFormDrawer } from "./MicroserviceFormDrawer";
import type { MicroserviceFormValues } from "@/schemas";
import { QueryServicesPanel } from "./QueryServicesPanel";

export function MicroservicesListPage() {
  const microservices = useMicroservices();
  const createMs = useCreateMicroservice();
  const updateMs = useUpdateMicroservice();
  const deleteMs = useDeleteMicroservice();
  const toast = useToast();
  const [editing, setEditing] = useState<MicroserviceResponse | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<MicroserviceResponse | null>(null);

  async function handleSubmit(values: MicroserviceFormValues & { id?: number }) {
    try {
      if (values.id) {
        await updateMs.mutateAsync(values as Parameters<typeof updateMs.mutateAsync>[0]);
        toast.show("Microservicio actualizado", "success");
      } else {
        await createMs.mutateAsync(values as Parameters<typeof createMs.mutateAsync>[0]);
        toast.show(values.kind === "QUERY"
          ? "Microservicio QUERY creado; aprovisionando…"
          : "Microservicio creado", "success");
      }
      setEditing(null);
      setCreating(false);
    } catch (err) {
      // The Form<T> wrapper already toasts the error; we
      // just keep the drawer open so the user can fix it.
      console.error("microservice save failed", err);
    }
  }

  async function confirmDelete() {
    if (!deleting) return;
    await deleteMs.mutateAsync(deleting.id);
    toast.show("Microservicio eliminado", "success");
    setDeleting(null);
  }

  const columns: Column<MicroserviceResponse>[] = [
    { key: "serviceId", header: "Service ID", render: (m) => m.serviceId },
    {
      key: "kind",
      header: "Tipo",
      render: (m) => <KindBadge kind={m.kind} />,
    },
    {
      key: "requestUri",
      header: "Request URI",
      render: (m) => m.requestUri,
    },
    {
      key: "target",
      header: "Target",
      render: (m) =>
        m.kind === "QUERY"
          ? `${m.dialect ?? "—"}${m.instanceName ? ` (${m.instanceName})` : ""}`
          : `${m.targetUrlHost}:${m.targetUrlPort}${m.targetUriPath}`,
    },
    {
      key: "description",
      header: "Descripción",
      render: (m) => m.description || "—",
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (m) => (
        <div className="flex justify-end gap-2">
          <Button size="sm" variant="secondary" onClick={() => setEditing(m)}>
            Editar
          </Button>
          <Button size="sm" variant="danger" onClick={() => setDeleting(m)}>
            Eliminar
          </Button>
        </div>
      ),
    },
  ];

  return (
    <section>
      <header className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold text-slate-900">Microservicios</h1>
        <Button onClick={() => setCreating(true)}>+ Nuevo microservicio</Button>
      </header>
      <Table
        columns={columns}
        rows={microservices.data ?? []}
        rowKey={(m) => m.id}
        loading={microservices.isLoading}
        empty="Aún no hay microservicios."
      />
      <div className="mt-8">
        <QueryServicesPanel rows={microservices.data ?? []} />
      </div>
      <MicroserviceFormDrawer
        open={creating || editing !== null}
        microservice={editing}
        onClose={() => {
          setCreating(false);
          setEditing(null);
        }}
        onSubmit={handleSubmit}
      />
      <Modal
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        title="Eliminar microservicio"
        description={`¿Seguro que quieres eliminar "${deleting?.serviceId}"? Esta acción no se puede deshacer.`}
        footer={
          <>
            <Button variant="secondary" onClick={() => setDeleting(null)}>
              Cancelar
            </Button>
            <Button
              variant="danger"
              loading={deleteMs.isPending}
              onClick={() => void confirmDelete()}
            >
              Eliminar
            </Button>
          </>
        }
      >
        <p className="text-sm text-slate-600">
          {deleting?.kind === "QUERY"
            ? "Esto también des-aprovisiona el contenedor asociado vía el sidecar."
            : "Si hay endpoints vinculados, el backend podría rechazar la operación."}
        </p>
      </Modal>
    </section>
  );
}

function KindBadge({ kind }: { kind: MicroserviceKind }) {
  return (
    <span
      className={[
        "inline-flex items-center rounded px-2 py-0.5 text-xs font-medium",
        kind === "QUERY"
          ? "bg-violet-100 text-violet-800"
          : "bg-slate-100 text-slate-700",
      ].join(" ")}
    >
      {kind}
    </span>
  );
}
