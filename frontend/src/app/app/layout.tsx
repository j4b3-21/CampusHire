import { AuthGuard } from "@/components/auth/AuthGuard";

export default function AuthenticatedLayout({ children }: { children: React.ReactNode }) {
  return <AuthGuard>{children}</AuthGuard>;
}
