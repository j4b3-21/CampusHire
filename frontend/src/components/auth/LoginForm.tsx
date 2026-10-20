"use client";

import * as React from "react";
import { Chrome, Eye, EyeOff, Mail } from "lucide-react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { useAuth } from "@/hooks/use-auth";
import type { LoginFormValues } from "@/types/auth";

export function LoginForm() {
  const router = useRouter();
  const { login, authenticated, loading } = useAuth();
  const [showPassword, setShowPassword] = React.useState(false);
  const [values, setValues] = React.useState<LoginFormValues>({ email: "", password: "" });
  const [submitting, setSubmitting] = React.useState(false);

  React.useEffect(() => {
    if (authenticated && !loading) {
      router.replace("/dashboard");
    }
  }, [authenticated, loading, router]);

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSubmitting(true);
    try {
      await login(values);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card className="border-white/10 bg-white/5 backdrop-blur-xl">
      <CardHeader className="space-y-3">
        <div className="inline-flex w-fit items-center rounded-full border border-border/60 bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
          CampusHire authentication
        </div>
        <CardTitle className="text-3xl">Sign in to CampusHire</CardTitle>
        <CardDescription>Use your college email and Keycloak-backed identity.</CardDescription>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={onSubmit}>
          <div className="space-y-2">
            <Label htmlFor="email">Email</Label>
            <div className="relative">
              <Mail className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
              <Input
                id="email"
                type="email"
                placeholder="user@example.com"
                className="pl-9"
                value={values.email}
                onChange={(event) => setValues((current) => ({ ...current, email: event.target.value }))}
                autoComplete="email"
                required
              />
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="password">Password</Label>
            <div className="relative">
              <Input
                id="password"
                type={showPassword ? "text" : "password"}
                placeholder="Enter your password"
                className="pr-10"
                value={values.password}
                onChange={(event) => setValues((current) => ({ ...current, password: event.target.value }))}
                autoComplete="current-password"
                required
              />
              <button
                type="button"
                onClick={() => setShowPassword((current) => !current)}
                className="absolute right-3 top-3 text-muted-foreground transition hover:text-foreground"
                aria-label={showPassword ? "Hide password" : "Show password"}
              >
                {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
            <p className="text-xs text-muted-foreground">Password is handled by Keycloak authentication.</p>
          </div>

          <Button type="submit" className="w-full" disabled={submitting}>
            {submitting ? "Signing in..." : "Sign In"}
          </Button>

          <div className="flex items-center gap-3">
            <Separator className="flex-1" />
            <span className="text-xs uppercase tracking-[0.2em] text-muted-foreground">or</span>
            <Separator className="flex-1" />
          </div>

          <Button
            type="button"
            variant="outline"
            className="w-full border-white/10 bg-white/5"
            onClick={() => login(values, "google")}
          >
            <Chrome className="h-4 w-4" />
            Continue with Google
          </Button>
        </form>
      </CardContent>
      <CardFooter className="flex flex-col gap-3 border-t border-border/60 pt-6 text-sm text-muted-foreground sm:flex-row sm:justify-between">
        <a className="font-medium text-foreground hover:underline" href="/register">
          Create Account
        </a>
        <a className="font-medium text-foreground hover:underline" href="/forgot-password">
          Forgot Password?
        </a>
      </CardFooter>
    </Card>
  );
}
