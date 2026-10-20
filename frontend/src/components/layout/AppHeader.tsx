"use client";

import * as React from "react";
import { ChevronDown, MoonStar, SunMedium, LogOut } from "lucide-react";
import { useTheme } from "next-themes";

import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useAuth } from "@/hooks/use-auth";

export function AppHeader() {
  const { user, roles, logout } = useAuth();
  const { theme, setTheme } = useTheme();

  const initials = [user?.firstName?.[0], user?.lastName?.[0]]
    .filter(Boolean)
    .join("")
    .toUpperCase();

  return (
    <header className="flex items-center justify-between border-b border-border/70 bg-background/80 px-4 py-4 backdrop-blur md:px-6">
      <div>
        <p className="text-sm text-muted-foreground">Welcome back</p>
        <h1 className="text-xl font-semibold tracking-tight">{user ? `${user.firstName} ${user.lastName}` : "CampusHire Dashboard"}</h1>
      </div>

      <div className="flex items-center gap-3">
        {roles.slice(0, 2).map((role) => (
          <Badge key={role}>{role}</Badge>
        ))}

        <Button variant="outline" size="icon" onClick={() => setTheme(theme === "dark" ? "light" : "dark")} aria-label="Toggle theme">
          {theme === "dark" ? <SunMedium className="h-4 w-4" /> : <MoonStar className="h-4 w-4" />}
        </Button>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" className="h-11 rounded-full px-3">
              <Avatar className="h-7 w-7">
                <AvatarImage src={user?.profilePicture ?? undefined} alt={user?.username ?? "User"} />
                <AvatarFallback>{initials || "CU"}</AvatarFallback>
              </Avatar>
              <span className="hidden md:inline">{user?.username ?? "Account"}</span>
              <ChevronDown className="h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            <DropdownMenuItem onClick={() => window.location.assign("/login")}>Switch account</DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={() => void logout()}>
              <LogOut className="mr-2 h-4 w-4" />
              Sign out
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </header>
  );
}
