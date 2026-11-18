import { Controller, Get, Param, Req, Res } from '@nestjs/common';
import { ApiOperation, ApiTags } from '@nestjs/swagger';
import { SkipThrottle } from '@nestjs/throttler';
import type { Request, Response } from 'express';
import type { Readable } from 'node:stream';
import { BackendClient } from './backend.client';
import { describe } from './http-exception.filter';
import { correlationIdOf } from './correlation.middleware';

const ID = /^[A-Za-z0-9-]{1,64}$/;

/**
 * Server-Sent Events proxy. Bytes are forwarded as soon as they arrive (no buffering, no re-parsing), so
 * each event reaches the browser individually. Closing the browser connection aborts the upstream request,
 * and Last-Event-ID / ?lastEventId are forwarded so reconnections resume where they stopped.
 */
@ApiTags('events')
@SkipThrottle()
@Controller('api/experiments')
export class SseController {
  constructor(private readonly backend: BackendClient) {}

  @Get(':id/events')
  @ApiOperation({ summary: 'Experiment events: SSE stream (Accept: text/event-stream) or JSON list' })
  async events(@Param('id') id: string, @Req() req: Request, @Res() res: Response): Promise<void> {
    if (!ID.test(id)) {
      res.status(400).json({ status: 400, code: 'VALIDATION_FAILED', message: 'Invalid experiment id', details: [], correlationId: correlationIdOf(req) });
      return;
    }
    const wantsStream = (req.header('accept') ?? '').includes('text/event-stream');
    const query = { ...req.query } as Record<string, unknown>;
    delete query.instance;
    if (!wantsStream) {
      const upstream = await this.backend.request(req, `/api/experiments/${id}/events`, query);
      res.status(upstream.status).type(String(upstream.headers['content-type'] ?? 'application/json')).send(Buffer.from(upstream.data));
      return;
    }

    const instance = this.backend.resolveInstance(req);
    const abort = new AbortController();
    req.on('close', () => abort.abort());
    try {
      const upstream = await this.backend.client(instance).get<Readable>(`/api/experiments/${id}/events`, {
        params: query,
        headers: { ...this.backend.forwardHeaders(req), accept: 'text/event-stream' },
        responseType: 'stream',
        timeout: 0,
        signal: abort.signal,
      });
      if (upstream.status !== 200) {
        const chunks: Buffer[] = [];
        for await (const chunk of upstream.data) chunks.push(Buffer.from(chunk));
        res.status(upstream.status).type('application/json').send(Buffer.concat(chunks));
        return;
      }
      res.status(200);
      res.setHeader('Content-Type', 'text/event-stream; charset=utf-8');
      res.setHeader('Cache-Control', 'no-cache, no-transform');
      res.setHeader('Connection', 'keep-alive');
      res.setHeader('X-Accel-Buffering', 'no');
      res.flushHeaders();
      upstream.data.on('data', (chunk: Buffer) => res.write(chunk));
      upstream.data.on('end', () => res.end());
      upstream.data.on('error', () => res.end());
    } catch (error) {
      if (abort.signal.aborted) return; // client went away
      const { status, code, message } = describe(error);
      if (!res.headersSent) {
        res.status(status).json({ timestamp: new Date().toISOString(), status, code, message, details: [], correlationId: correlationIdOf(req), path: req.path });
      } else {
        res.end();
      }
    }
  }
}
