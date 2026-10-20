import { AdminSection } from "@/components/app/admin-section";

export default async function AdminSectionPage({ params }: { params: Promise<{ orgId: string; section: string }> }) {
  const { section } = await params;
  return <AdminSection section={section} />;
}
