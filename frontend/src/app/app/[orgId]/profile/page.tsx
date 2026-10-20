import type { Metadata } from "next";

import { Profile } from "@/components/app/profile";

export const metadata: Metadata = { title: "Profile" };

export default function ProfilePage() {
  return <Profile />;
}
