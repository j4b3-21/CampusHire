import type { Metadata } from "next";

import { KeycloakRedirect } from "@/components/auth/KeycloakRedirect";

export const metadata: Metadata = { title: "Reset password" };

export default function ForgotPasswordPage() {
  return <KeycloakRedirect action="reset-password" />;
}
