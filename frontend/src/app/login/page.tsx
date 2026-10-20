import { PublicGuard } from "@/components/auth/AuthGuard";
import { LoginForm } from "@/components/auth/LoginForm";

export default function LoginPage() {
  return (
    <PublicGuard>
      <main className="min-h-screen bg-[radial-gradient(circle_at_top,_rgba(59,130,246,0.22),_transparent_30%),linear-gradient(180deg,_#020617,_#0f172a)] px-4 py-10 text-white">
        <div className="mx-auto grid min-h-[calc(100vh-5rem)] max-w-6xl items-center gap-12 lg:grid-cols-2">
          <section className="space-y-6">
            <span className="inline-flex w-fit rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs font-medium uppercase tracking-[0.22em] text-sky-200">
              CampusHire
            </span>
            <h1 className="max-w-2xl text-4xl font-semibold tracking-tight md:text-6xl">
              Placement operations that feel modern, secure, and fast.
            </h1>
            <p className="max-w-xl text-base leading-7 text-slate-300 md:text-lg">
              Manage institute users, recruiters, students, and placement workflows on a role-aware SaaS dashboard backed by Keycloak and Spring Boot.
            </p>
            <div className="grid gap-3 text-sm text-slate-300 sm:grid-cols-2">
              <div className="rounded-2xl border border-white/10 bg-white/5 p-4">SSO login with Keycloak and Google</div>
              <div className="rounded-2xl border border-white/10 bg-white/5 p-4">Protected backend APIs with JWT validation</div>
              <div className="rounded-2xl border border-white/10 bg-white/5 p-4">Role-aware dashboards and access control</div>
              <div className="rounded-2xl border border-white/10 bg-white/5 p-4">Responsive dark-mode SaaS UI</div>
            </div>
          </section>
          <section className="mx-auto w-full max-w-md">
            <LoginForm />
          </section>
        </div>
      </main>
    </PublicGuard>
  );
}
