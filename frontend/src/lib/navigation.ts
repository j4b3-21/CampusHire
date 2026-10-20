import {
  Award,
  Boxes,
  Briefcase,
  Building,
  Building2,
  CalendarRange,
  ClipboardList,
  Contact,
  GraduationCap,
  LayoutDashboard,
  Layers,
  ListTodo,
  MessagesSquare,
  ScrollText,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  Upload,
  type LucideIcon,
} from "lucide-react";

export interface ModuleNavItem {
  moduleKey: string;
  label: string;
  icon: LucideIcon;
}

export interface NavGroupConfig {
  key: string;
  label: string;
  modules: ModuleNavItem[];
}

export const NAV_GROUPS: NavGroupConfig[] = [
  {
    key: "placement",
    label: "Placement",
    modules: [
      { moduleKey: "drive", label: "Drives", icon: CalendarRange },
      { moduleKey: "application", label: "Applications", icon: ClipboardList },
      { moduleKey: "interview", label: "Interviews", icon: MessagesSquare },
      { moduleKey: "offer", label: "Offers", icon: Award },
      { moduleKey: "job", label: "Jobs", icon: Briefcase },
    ],
  },
  {
    key: "students",
    label: "Students",
    modules: [
      { moduleKey: "student", label: "Students", icon: GraduationCap },
      { moduleKey: "department", label: "Departments", icon: Building },
      { moduleKey: "batch", label: "Batches", icon: Layers },
    ],
  },
  {
    key: "crm",
    label: "CRM",
    modules: [
      { moduleKey: "company", label: "Companies", icon: Building2 },
      { moduleKey: "contact", label: "Contacts", icon: Contact },
      { moduleKey: "follow_up", label: "Follow-ups", icon: ListTodo },
    ],
  },
];

export const DASHBOARD_NAV = { label: "Dashboard", icon: LayoutDashboard };
export const OTHER_MODULE_ICON = Boxes;

export interface AdminNavItem {
  section: string;
  label: string;
  icon: LucideIcon;
  description: string;
}

export const ADMIN_NAV: AdminNavItem[] = [
  {
    section: "import",
    label: "Data import",
    icon: Upload,
    description: "Upload students, companies and shortlists from Excel or CSV, with a dry run and an error report.",
  },
  {
    section: "roles",
    label: "Roles & access",
    icon: ShieldCheck,
    description: "Decide what placement staff, coordinators, students and recruiters can see and change.",
  },
  {
    section: "customize",
    label: "Customize modules",
    icon: SlidersHorizontal,
    description: "Add fields, arrange form layouts and edit pick-list options without a deployment.",
  },
  {
    section: "audit",
    label: "Audit log",
    icon: ScrollText,
    description: "Every change to records, roles and settings, with who made it and when.",
  },
  {
    section: "settings",
    label: "Settings",
    icon: Settings,
    description: "Institute name, allowed student email domains and placement policy.",
  },
];

const ICONS = new Map(NAV_GROUPS.flatMap((group) => group.modules.map((item) => [item.moduleKey, item.icon] as const)));

export function moduleIcon(moduleKey: string): LucideIcon {
  return ICONS.get(moduleKey) ?? OTHER_MODULE_ICON;
}

export const orgPath = (orgId: string, path = "") => `/app/${orgId}${path}`;
export const modulePath = (orgId: string, moduleKey: string, path = "") => `/app/${orgId}/m/${moduleKey}${path}`;
export const recordPath = (orgId: string, moduleKey: string, recordId: string, path = "") =>
  `/app/${orgId}/m/${moduleKey}/${recordId}${path}`;
