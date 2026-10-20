import { ModuleEditorPage } from "@/components/admin/modules";

export default async function ModuleEditorRoute({ params }: { params: Promise<{ orgId: string; moduleKey: string }> }) {
  const { moduleKey } = await params;
  return <ModuleEditorPage moduleKey={moduleKey} />;
}
