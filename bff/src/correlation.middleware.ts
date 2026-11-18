import { Injectable, NestMiddleware } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import type { NextFunction, Request, Response } from 'express';

export const CORRELATION_HEADER = 'x-correlation-id';
const SAFE_ID = /^[A-Za-z0-9._-]{1,64}$/;

export function correlationIdOf(req: Request): string {
  return (req as Request & { correlationId?: string }).correlationId ?? 'unknown';
}

/** Accepts a well-formed incoming X-Correlation-Id or generates one; echoes it on every response. */
@Injectable()
export class CorrelationIdMiddleware implements NestMiddleware {
  use(req: Request, res: Response, next: NextFunction): void {
    const incoming = req.header(CORRELATION_HEADER);
    const id = incoming && SAFE_ID.test(incoming) ? incoming : randomUUID();
    (req as Request & { correlationId?: string }).correlationId = id;
    res.setHeader('X-Correlation-Id', id);
    const started = Date.now();
    res.on('finish', () => {
      // structured access log (no bodies, no secrets)
      process.stdout.write(
        JSON.stringify({
          ts: new Date().toISOString(),
          level: 'info',
          msg: 'request',
          method: req.method,
          path: req.path,
          status: res.statusCode,
          durationMs: Date.now() - started,
          correlationId: id,
        }) + '\n',
      );
    });
    next();
  }
}
