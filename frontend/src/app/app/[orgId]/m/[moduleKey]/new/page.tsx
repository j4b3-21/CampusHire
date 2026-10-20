import { ModuleNewPage } from "@/components/records/module-pages";

export default async function NewRecordRoute({ params }: { params: Promise<{ orgId: string; moduleKey: string }> }) {
  const { moduleKey } = await params;
  return <ModuleNewPage moduleKey={moduleKey} />;
}
