import type { Metadata } from "next";

import { KeycloakRedirect } from "@/components/auth/KeycloakRedirect";

export const metadata: Metadata = { title: "Create account" };

export default function RegisterPage() {
  return <KeycloakRedirect action="register" />;
}
