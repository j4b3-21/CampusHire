import { AppShell } from "@/components/layout/app-shell";

export default async function OrgLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ orgId: string }>;
}) {
  const { orgId } = await params;
  return <AppShell orgId={orgId}>{children}</AppShell>;
}
