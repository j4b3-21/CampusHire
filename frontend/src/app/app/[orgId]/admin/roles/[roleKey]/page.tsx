import { RoleEditorPage } from "@/components/admin/roles";

export default async function RoleEditorRoute({ params }: { params: Promise<{ orgId: string; roleKey: string }> }) {
  const { roleKey } = await params;
  return <RoleEditorPage roleKey={roleKey} />;
}
