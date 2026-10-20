import { ModuleEditPage } from "@/components/records/module-pages";

export default async function EditRecordRoute({
  params,
}: {
  params: Promise<{ orgId: string; moduleKey: string; recordId: string }>;
}) {
  const { moduleKey, recordId } = await params;
  return <ModuleEditPage moduleKey={moduleKey} recordId={recordId} />;
}
