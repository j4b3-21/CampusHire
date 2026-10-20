import { redirect } from "next/navigation";

/** The proxy normally redirects `/` already; this is the fallback when it is bypassed. */
export default function Home() {
  redirect("/app");
}
