"use client";

import * as React from "react";
import { MailOpen } from "lucide-react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "@/hooks/use-auth";
import type { ForgotPasswordFormValues } from "@/types/auth";

export function ForgotPasswordForm() {
  const router = useRouter();
  const { forgotPassword, authenticated, loading } = useAuth();
  const [values, setValues] = React.useState<ForgotPasswordFormValues>({ email: "" });
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
      await forgotPassword(values);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card className="border-white/10 bg-white/5 backdrop-blur-xl">
      <CardHeader className="space-y-3">
        <div className="inline-flex w-fit items-center rounded-full border border-border/60 bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
          Reset password
        </div>
        <CardTitle className="text-3xl">Recover your account</CardTitle>
        <CardDescription>We will route you to Keycloak&apos;s password reset flow.</CardDescription>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={onSubmit}>
          <div className="space-y-2">
            <Label htmlFor="email">Email</Label>
            <div className="relative">
              <MailOpen className="pointer-events-none absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
              <Input
                id="email"
                type="email"
                className="pl-9"
                value={values.email}
                onChange={(event) => setValues((current) => ({ ...current, email: event.target.value }))}
                placeholder="user@example.com"
                required
              />
            </div>
          </div>

          <Button type="submit" className="w-full" disabled={submitting}>
            {submitting ? "Redirecting..." : "Reset Password"}
          </Button>
        </form>
      </CardContent>
      <CardFooter className="border-t border-border/60 pt-6 text-sm text-muted-foreground">
        Remembered your password? <a href="/login" className="ml-1 font-medium text-foreground hover:underline">Back to sign in</a>
      </CardFooter>
    </Card>
  );
}
