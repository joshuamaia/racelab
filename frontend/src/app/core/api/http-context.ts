import { HttpContext, HttpContextToken } from '@angular/common/http';

/** When true the error interceptor does not show a toast (caller handles the error). */
export const SILENT_ERRORS = new HttpContextToken<boolean>(() => false);

/** Target backend instance (sent as X-RaceLab-Instance). null = BFF default. */
export const TARGET_INSTANCE = new HttpContextToken<string | null>(() => null);

export const INSTANCE_HEADER = 'X-RaceLab-Instance';

export function apiContext(opts: { silent?: boolean; instance?: string | null } = {}): HttpContext {
  const ctx = new HttpContext();
  if (opts.silent) ctx.set(SILENT_ERRORS, true);
  if (opts.instance) ctx.set(TARGET_INSTANCE, opts.instance);
  return ctx;
}
