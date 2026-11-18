import { All, Controller, Get, Req, Res } from '@nestjs/common';
import { ApiExcludeEndpoint, ApiOperation, ApiTags } from '@nestjs/swagger';
import type { Request, Response } from 'express';
import { BackendClient } from './backend.client';

/** Response headers relayed from the backend. */
const RELAYED_HEADERS = ['content-type', 'content-disposition', 'retry-after'];

@ApiTags('bff')
@Controller()
export class ProxyController {
  constructor(private readonly backend: BackendClient) {}

  @Get('health')
  @ApiOperation({ summary: 'BFF liveness' })
  health(): { status: string } {
    return { status: 'UP' };
  }

  @Get('api/instances')
  @ApiOperation({ summary: 'Aggregates /api/system/info of every configured backend instance' })
  async instances(@Req() req: Request): Promise<unknown[]> {
    return Promise.all(
      this.backend.instances().map(async (instance) => {
        try {
          const r = await this.backend.client(instance).get('/api/system/info', {
            headers: this.backend.forwardHeaders(req),
            timeout: 2500,
            responseType: 'json',
          });
          const ok = r.status === 200;
          return { instanceId: instance.instanceId, url: instance.url, available: ok, info: ok ? r.data : null };
        } catch {
          return { instanceId: instance.instanceId, url: instance.url, available: false, info: null };
        }
      }),
    );
  }

  /** Generic REST proxy for /api/** (the BFF owns no business or transactional rule). */
  @All('api/*path')
  @ApiExcludeEndpoint()
  async proxy(@Req() req: Request, @Res() res: Response): Promise<void> {
    const path = req.originalUrl.split('?')[0];
    const query = { ...req.query } as Record<string, unknown>;
    delete query.instance;
    const upstream = await this.backend.request(req, path, query);
    for (const h of RELAYED_HEADERS) {
      const v = upstream.headers[h];
      if (v) res.setHeader(h, String(v));
    }
    res.setHeader('X-RaceLab-Instance', this.backend.resolveInstance(req).instanceId);
    res.status(upstream.status).send(Buffer.from(upstream.data ?? []));
  }
}
