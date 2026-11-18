import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MessageService } from 'primeng/api';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { ApiError } from '../models/api.models';
import { INSTANCE_HEADER, SILENT_ERRORS, TARGET_INSTANCE } from './http-context';

/** Adds X-RaceLab-Instance when a target instance is attached to the request context. */
export const instanceInterceptor: HttpInterceptorFn = (req, next) => {
  const instance = req.context.get(TARGET_INSTANCE);
  return next(instance ? req.clone({ setHeaders: { [INSTANCE_HEADER]: instance } }) : req);
};

/** Normalises any HttpErrorResponse into the contract's ApiError shape. */
export function toApiError(err: HttpErrorResponse, body: unknown = err.error): ApiError {
  if (body && typeof body === 'object' && 'message' in body) {
    const b = body as Partial<ApiError>;
    return {
      status: b.status ?? err.status,
      code: b.code ?? (err.status ? `HTTP_${err.status}` : 'NETWORK_ERROR'),
      message: b.message ?? err.message,
      details: b.details,
      correlationId: b.correlationId ?? err.headers?.get('X-Correlation-Id') ?? undefined,
      path: b.path,
      timestamp: b.timestamp,
      error: b.error,
    };
  }
  if (err.status === 0) {
    return { status: 0, code: 'NETWORK_ERROR', message: 'Cannot reach the RaceLab API. Is the stack running?' };
  }
  return {
    status: err.status,
    code: `HTTP_${err.status}`,
    message: err.statusText || 'Unexpected error',
    correlationId: err.headers?.get('X-Correlation-Id') ?? undefined,
  };
}

async function readBody(err: HttpErrorResponse): Promise<unknown> {
  if (err.error instanceof Blob) {
    try {
      return JSON.parse(await err.error.text());
    } catch {
      return null;
    }
  }
  return err.error;
}

/** Central error handling → PrimeNG toast with friendly message + technical code / correlationId. */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const messages = inject(MessageService);
  const silent = req.context.get(SILENT_ERRORS);
  return next(req).pipe(
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse)) return throwError(() => err);
      return from(readBody(err)).pipe(
        switchMap((body) => {
          const apiError = toApiError(err, body);
          if (!silent) {
            const technical = [apiError.code, apiError.correlationId ? `correlationId ${apiError.correlationId}` : null]
              .filter(Boolean)
              .join(' · ');
            const details = apiError.details?.length ? `\n${apiError.details.join('\n')}` : '';
            messages.add({
              severity: apiError.status >= 500 || apiError.status === 0 ? 'error' : 'warn',
              summary: apiError.message,
              detail: `${technical}${details}`,
              life: 8000,
            });
          }
          return throwError(() => Object.assign(err, { apiError }));
        }),
      );
    }),
  );
};

export function apiErrorOf(err: unknown): ApiError | null {
  if (err && typeof err === 'object' && 'apiError' in err) return (err as { apiError: ApiError }).apiError;
  if (err instanceof HttpErrorResponse) return toApiError(err);
  return null;
}
