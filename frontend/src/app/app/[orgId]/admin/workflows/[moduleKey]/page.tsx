import { WorkflowEditorPage } from "@/components/admin/workflow-editor";

export default async function WorkflowEditorRoute({ params }: { params: Promise<{ orgId: string; moduleKey: string }> }) {
  const { moduleKey } = await params;
  return <WorkflowEditorPage moduleKey={moduleKey} />;
}
