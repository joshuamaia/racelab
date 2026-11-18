import { HttpException, HttpStatus, Inject, Injectable } from '@nestjs/common';
import axios, { AxiosInstance, AxiosResponse } from 'axios';
import type { Request } from 'express';
import { BFF_CONFIG, BackendInstance, BffConfig } from './config';
import { correlationIdOf } from './correlation.middleware';

export const INSTANCE_HEADER = 'x-racelab-instance';

/** Headers forwarded to the backend (allow-list: the BFF never leaks arbitrary client headers). */
const FORWARDED_REQUEST_HEADERS = ['content-type', 'accept', 'last-event-id'];

/** Thin Axios wrapper: picks the backend instance and propagates the correlation id. */
@Injectable()
export class BackendClient {
  private readonly clients = new Map<string, AxiosInstance>();

  constructor(@Inject(BFF_CONFIG) private readonly config: BffConfig) {
    for (const instance of config.instances) {
      this.clients.set(
        instance.instanceId,
        axios.create({ baseURL: instance.url, timeout: config.timeoutMs, validateStatus: () => true, maxRedirects: 0 }),
      );
    }
  }

  instances(): BackendInstance[] {
    return this.config.instances;
  }

  /** Target instance from the X-RaceLab-Instance header or the ?instance= query param (EventSource cannot set headers). */
  resolveInstance(req: Request): BackendInstance {
    const requested = (req.header(INSTANCE_HEADER) ?? (req.query.instance as string | undefined) ?? '').trim();
    const id = requested || this.config.defaultInstance;
    const instance = this.config.instances.find((i) => i.instanceId === id);
    if (!instance) {
      throw new HttpException(
        {
          status: 400,
          code: 'UNKNOWN_INSTANCE',
          message: `Unknown backend instance '${id}'. Available: ${this.config.instances.map((i) => i.instanceId).join(', ')}`,
        },
        HttpStatus.BAD_REQUEST,
      );
    }
    return instance;
  }

  client(instance: BackendInstance): AxiosInstance {
    return this.clients.get(instance.instanceId)!;
  }

  forwardHeaders(req: Request): Record<string, string> {
    const headers: Record<string, string> = { 'X-Correlation-Id': correlationIdOf(req) };
    for (const name of FORWARDED_REQUEST_HEADERS) {
      const value = req.header(name);
      if (value) headers[name] = value;
    }
    const forwardedFor = req.ip ?? req.socket.remoteAddress;
    if (forwardedFor) headers['X-Forwarded-For'] = forwardedFor;
    return headers;
  }

  async request(req: Request, path: string, query: Record<string, unknown>): Promise<AxiosResponse<Buffer>> {
    const instance = this.resolveInstance(req);
    const hasBody = !['GET', 'HEAD', 'DELETE'].includes(req.method) && req.body !== undefined;
    return this.client(instance).request<Buffer>({
      method: req.method as 'GET',
      url: path,
      params: query,
      data: hasBody ? JSON.stringify(req.body ?? {}) : undefined,
      headers: { ...this.forwardHeaders(req), ...(hasBody ? { 'content-type': 'application/json' } : {}) },
      responseType: 'arraybuffer',
    });
  }
}
