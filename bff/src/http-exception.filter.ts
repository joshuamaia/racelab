import { ArgumentsHost, Catch, ExceptionFilter, HttpException } from '@nestjs/common';
import { ThrottlerException } from '@nestjs/throttler';
import { AxiosError } from 'axios';
import type { Request, Response } from 'express';
import { correlationIdOf } from './correlation.middleware';

const REASONS: Record<number, string> = {
  400: 'Bad Request',
  404: 'Not Found',
  413: 'Payload Too Large',
  429: 'Too Many Requests',
  500: 'Internal Server Error',
  502: 'Bad Gateway',
  503: 'Service Unavailable',
  504: 'Gateway Timeout',
};

/** Centralised error handling: every error leaves the BFF in the shared RaceLab error format. */
@Catch()
export class GlobalExceptionFilter implements ExceptionFilter {
  catch(exception: unknown, host: ArgumentsHost): void {
    const ctx = host.switchToHttp();
    const req = ctx.getRequest<Request>();
    const res = ctx.getResponse<Response>();
    if (res.headersSent) {
      res.end();
      return;
    }
    const { status, code, message, details } = describe(exception);
    if (status >= 500) {
      process.stderr.write(
        JSON.stringify({ ts: new Date().toISOString(), level: 'error', msg: message, code, path: req.path, correlationId: correlationIdOf(req) }) +
          '\n',
      );
    }
    res.status(status).json({
      timestamp: new Date().toISOString(),
      status,
      error: REASONS[status] ?? 'Error',
      code,
      message,
      details,
      correlationId: correlationIdOf(req),
      path: req.originalUrl.split('?')[0],
    });
  }
}

export function describe(exception: unknown): { status: number; code: string; message: string; details: string[] } {
  if (exception instanceof ThrottlerException) {
    return { status: 429, code: 'RATE_LIMITED', message: 'Too many requests; slow down', details: [] };
  }
  if (exception instanceof AxiosError || (exception as AxiosError)?.isAxiosError) {
    const err = exception as AxiosError;
    if (err.code === 'ECONNABORTED' || err.code === 'ETIMEDOUT') {
      return { status: 504, code: 'BACKEND_TIMEOUT', message: 'The experiment engine did not answer in time', details: [] };
    }
    return {
      status: 503,
      code: 'BACKEND_UNAVAILABLE',
      message: 'The experiment engine is not reachable',
      details: [err.code ?? err.message],
    };
  }
  if (exception instanceof HttpException) {
    const body = exception.getResponse();
    const status = exception.getStatus();
    if (typeof body === 'object' && body !== null) {
      const b = body as { code?: string; message?: string | string[]; details?: string[] };
      const msg = Array.isArray(b.message) ? b.message.join('; ') : (b.message ?? exception.message);
      return { status, code: b.code ?? (status === 404 ? 'NOT_FOUND' : 'BFF_ERROR'), message: msg, details: b.details ?? [] };
    }
    return { status, code: 'BFF_ERROR', message: String(body), details: [] };
  }
  return { status: 500, code: 'INTERNAL_ERROR', message: 'Unexpected BFF error', details: [] };
}
