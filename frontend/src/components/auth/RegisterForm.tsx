"use client";

import * as React from "react";
import { UserRoundPlus } from "lucide-react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "@/hooks/use-auth";
import type { RegisterFormValues } from "@/types/auth";

export function RegisterForm() {
  const router = useRouter();
  const { register, authenticated, loading } = useAuth();
  const [values, setValues] = React.useState<RegisterFormValues>({ firstName: "", lastName: "", email: "" });
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
      await register(values);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Card className="border-white/10 bg-white/5 backdrop-blur-xl">
      <CardHeader className="space-y-3">
        <div className="inline-flex w-fit items-center rounded-full border border-border/60 bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
          Create your CampusHire account
        </div>
        <CardTitle className="text-3xl">Register your college profile</CardTitle>
        <CardDescription>We will hand you off to Keycloak for secure account creation.</CardDescription>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={onSubmit}>
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="firstName">First Name</Label>
              <Input
                id="firstName"
                value={values.firstName}
                onChange={(event) => setValues((current) => ({ ...current, firstName: event.target.value }))}
                placeholder="John"
                required
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="lastName">Last Name</Label>
              <Input
                id="lastName"
                value={values.lastName}
                onChange={(event) => setValues((current) => ({ ...current, lastName: event.target.value }))}
                placeholder="Doe"
                required
              />
            </div>
          </div>
          <div className="space-y-2">
            <Label htmlFor="email">Email</Label>
            <Input
              id="email"
              type="email"
              value={values.email}
              onChange={(event) => setValues((current) => ({ ...current, email: event.target.value }))}
              placeholder="student@college.edu"
              autoComplete="email"
              required
            />
          </div>

          <Button type="submit" className="w-full" disabled={submitting}>
            <UserRoundPlus className="h-4 w-4" />
            {submitting ? "Opening registration..." : "Submit Registration"}
          </Button>
        </form>
      </CardContent>
      <CardFooter className="border-t border-border/60 pt-6 text-sm text-muted-foreground">
        Already have an account? <a href="/login" className="ml-1 font-medium text-foreground hover:underline">Sign in</a>
      </CardFooter>
    </Card>
  );
}
