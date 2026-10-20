import type { FieldViolation, ProblemDetails } from "@/types/api";

const FRIENDLY_MESSAGES: Record<string, string> = {
  network_error: "Can't reach the CampusHire server. Check your connection and try again.",
  unauthenticated: "Your session has expired. Sign in again to continue.",
  access_denied: "You don't have permission to do that.",
  organization_inactive: "This institute is suspended or archived.",
  not_found: "We couldn't find what you were looking for.",
  version_conflict: "Someone else changed this record in the meantime. Reload to get the latest version.",
  precondition_required: "This change needs the current version of the record. Reload and try again.",
  validation_failed: "Some values are invalid. Fix the highlighted fields and try again.",
  unique_violation: "Another record already uses this value.",
  record_referenced: "Other records still point to this one, so it can't be deleted.",
  record_linked: "This record is still linked to other records.",
  rate_limited: "Too many requests. Wait a moment and try again.",
  authorization_unavailable: "The permissions service is unavailable. Try again in a moment.",
  internal_error: "Something went wrong on the server. Try again, and contact support if it keeps happening.",
  comment_required: "This step needs a comment explaining the change.",
  role_in_use: "Members or pending invitations still use this role. Assign them another role first.",
  role_exists: "A role with this key already exists.",
  module_exists: "A module with this key already exists.",
  field_exists: "A field with this key already exists.",
  already_member: "This person is already a member of the institute.",
  invitation_pending: "There's already a pending invitation for this email address.",
  invitation_used: "This invitation has already been used.",
  invitation_expired: "This invitation has expired. Ask your placement cell for a new one.",
  invitation_revoked: "This invitation was revoked. Ask your placement cell for a new one.",
  invitation_email_mismatch: "This invitation was sent to a different email address than the one you're signed in with.",
  email_not_verified: "Verify your email address before accepting the invitation.",
  last_owner: "An institute needs at least one owner.",
  drive_status_conflict: "The drive's status changed in the meantime. Reload to see where it is now.",
  drive_not_open: "This drive isn't open for applications.",
  registration_not_open: "Registration for this drive hasn't opened yet.",
  registration_closed: "Registration for this drive has closed.",
  not_eligible: "You're not eligible for this drive.",
  application_removed: "Your application to this drive was removed by the placement cell.",
  student_not_linked: "Your account isn't linked to a student record yet.",
  student_not_found: "Your placement cell hasn't added a student record with your email address yet.",
  student_already_claimed: "This student record is already linked to another account. Contact your placement cell.",
  domain_not_allowed: "Your email domain isn't allowed for this institute.",
  membership_revoked: "Your access to this institute was revoked. Contact your placement cell.",
  offer_not_pending: "This offer has already been answered.",
  offer_expired: "The response deadline for this offer has passed.",
  offer_policy_violation: "The placement policy doesn't allow another offer for this student.",
  import_in_progress: "Another import for this module is still running. Wait for it to finish.",
  import_not_finished: "The import is still running.",
  import_finished: "The import has already finished.",
  roll_column_missing: "No roll number column found. Add a header such as “Roll number” in the first rows.",
  no_roll_numbers: "The file doesn't contain any roll numbers.",
  too_many_rows: "The file has too many rows.",
  unreadable_file: "The file couldn't be read. Upload an XLSX, XLS or CSV file.",
  file_empty: "The file is empty.",
  empty_file: "The file is empty.",
  unsupported_file_type: "This file type isn't supported.",
  file_too_large: "The file is too large (25 MB at most).",
  duplicate_header: "Two columns have the same header. Rename one and upload again.",
  too_many_columns: "The file has too many columns.",
  invalid_format: "Upload an Excel (.xlsx) or CSV file.",
};

/** Error thrown for every non-2xx API response (and for network failures, with `status` 0). */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly title?: string;
  readonly detail?: string;
  readonly errors: FieldViolation[];
  readonly correlationId?: string;

  constructor(problem: ProblemDetails, fallbackCode = "http_error") {
    super(problem.detail || problem.title || `Request failed with status ${problem.status}`);
    this.name = "ApiError";
    this.status = problem.status;
    this.code = problem.code ?? fallbackCode;
    this.title = problem.title;
    this.detail = problem.detail;
    this.errors = problem.errors ?? [];
    this.correlationId = problem.correlationId;
  }

  static network(cause?: unknown) {
    const error = new ApiError({ status: 0, code: "network_error", title: "Network error" });
    if (cause instanceof Error) error.cause = cause;
    return error;
  }

  get isNetwork() {
    return this.status === 0;
  }
  get isUnauthorized() {
    return this.status === 401;
  }
  get isForbidden() {
    return this.status === 403;
  }
  get isNotFound() {
    return this.status === 404;
  }
  get isValidation() {
    return this.status === 400 && this.errors.length > 0;
  }
  get isConflict() {
    return this.status === 409;
  }
  get isVersionConflict() {
    return this.status === 412 || this.code === "version_conflict";
  }
  get isServerError() {
    return this.status >= 500;
  }

  /** User-facing sentence for toasts and error states. */
  get friendlyMessage() {
    if (this.code in FRIENDLY_MESSAGES && this.code !== "validation_failed") return FRIENDLY_MESSAGES[this.code];
    if (this.isValidation) return FRIENDLY_MESSAGES.validation_failed;
    if (this.detail) return this.detail;
    if (this.isServerError) return FRIENDLY_MESSAGES.internal_error;
    return this.message;
  }

  /**
   * Field errors keyed by field api key. Record payload violations come as `data.<field>`; the prefix is stripped.
   */
  fieldErrors(prefix = "data."): Record<string, string> {
    const result: Record<string, string> = {};
    for (const violation of this.errors) {
      const key = violation.field.startsWith(prefix) ? violation.field.slice(prefix.length) : violation.field;
      if (!result[key]) result[key] = violation.message;
    }
    return result;
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function errorMessage(error: unknown) {
  if (isApiError(error)) return error.friendlyMessage;
  if (error instanceof Error) return error.message;
  return "Something went wrong.";
}

/** Errors that the user did not cause and cannot fix in the current view. */
export function isUnexpectedError(error: unknown) {
  if (!isApiError(error)) return true;
  return error.isNetwork || error.isServerError || error.status === 429 || error.isForbidden;
}
