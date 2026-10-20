import type { Metadata } from "next";

import { InvitationAccept } from "@/components/app/invitation-accept";
import { AuthGuard } from "@/components/auth/AuthGuard";

export const metadata: Metadata = { title: "Invitation" };

export default async function InvitationRoute({ params }: { params: Promise<{ token: string }> }) {
  const { token } = await params;
  return (
    <AuthGuard>
      <InvitationAccept token={token} />
    </AuthGuard>
  );
}
