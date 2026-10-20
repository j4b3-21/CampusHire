import { PublicGuard } from "@/components/auth/AuthGuard";
import { ForgotPasswordForm } from "@/components/auth/ForgotPasswordForm";

export default function ForgotPasswordPage() {
  return (
    <PublicGuard>
      <main className="min-h-screen bg-[radial-gradient(circle_at_top,_rgba(168,85,247,0.18),_transparent_28%),linear-gradient(180deg,_#020617,_#111827)] px-4 py-10 text-white">
        <div className="mx-auto grid min-h-[calc(100vh-5rem)] max-w-6xl items-center gap-12 lg:grid-cols-2">
          <section className="space-y-6">
            <span className="inline-flex w-fit rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs font-medium uppercase tracking-[0.22em] text-sky-200">
              Account recovery
            </span>
            <h1 className="max-w-2xl text-4xl font-semibold tracking-tight md:text-6xl">
              Recover access without touching backend credentials.
            </h1>
            <p className="max-w-xl text-base leading-7 text-slate-300 md:text-lg">
              Password resets are always handled by Keycloak, keeping authentication policies and verification centralized.
            </p>
          </section>
          <section className="mx-auto w-full max-w-md">
            <ForgotPasswordForm />
          </section>
        </div>
      </main>
    </PublicGuard>
  );
}
