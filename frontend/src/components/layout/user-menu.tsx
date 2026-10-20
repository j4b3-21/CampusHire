"use client";

import Link from "next/link";
import { ExternalLink, LogOut, Monitor, Moon, Palette, Sun, UserRound } from "lucide-react";
import { useTheme } from "next-themes";

import { Avatar, AvatarFallback, initials } from "@/components/ui/avatar";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useAuth } from "@/hooks/use-auth";
import { accountUrl } from "@/lib/auth/keycloak";

export function UserMenu({ profileHref }: { profileHref?: string }) {
  const { profile, logout } = useAuth();
  const { theme, setTheme } = useTheme();
  const name = profile?.name ?? profile?.username ?? profile?.email ?? "Signed in";

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className="focus-ring rounded-full transition-opacity hover:opacity-90"
        aria-label={`Account menu for ${name}`}
      >
        <Avatar className="size-7">
          <AvatarFallback className="text-[11px]">{initials(profile?.name ?? profile?.email)}</AvatarFallback>
        </Avatar>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60">
        <DropdownMenuLabel className="flex flex-col gap-0.5 py-2">
          <span className="truncate text-[13px] font-medium text-foreground">{name}</span>
          {profile?.email ? <span className="truncate font-normal">{profile.email}</span> : null}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {profileHref ? (
          <DropdownMenuItem asChild>
            <Link href={profileHref}>
              <UserRound /> Profile
            </Link>
          </DropdownMenuItem>
        ) : null}
        <DropdownMenuItem asChild>
          <a href={accountUrl()} target="_blank" rel="noreferrer">
            <ExternalLink /> Password and security
          </a>
        </DropdownMenuItem>
        <DropdownMenuSub>
          <DropdownMenuSubTrigger>
            <Palette /> Theme
          </DropdownMenuSubTrigger>
          <DropdownMenuSubContent className="min-w-36">
            <DropdownMenuRadioGroup value={theme} onValueChange={setTheme}>
              <DropdownMenuRadioItem value="light">
                <Sun /> Light
              </DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="dark">
                <Moon /> Dark
              </DropdownMenuRadioItem>
              <DropdownMenuRadioItem value="system">
                <Monitor /> System
              </DropdownMenuRadioItem>
            </DropdownMenuRadioGroup>
          </DropdownMenuSubContent>
        </DropdownMenuSub>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => void logout()}>
          <LogOut /> Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
