import { INestApplication } from '@nestjs/common';
import http from 'node:http';
import { AddressInfo } from 'node:net';
import request from 'supertest';
import { createApp } from '../src/main';

/** A fake experiment engine: records what it receives and streams SSE frames on demand. */
class FakeBackend {
  server: http.Server;
  seen: { method: string; url: string; headers: http.IncomingHttpHeaders; body: string }[] = [];
  sseClosed = false;

  constructor(private readonly name: string) {
    this.server = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        this.seen.push({ method: req.method!, url: req.url!, headers: req.headers, body });
        if (req.url!.startsWith('/api/experiments/EXP-1/events') && req.headers.accept?.includes('text/event-stream')) {
          res.writeHead(200, { 'Content-Type': 'text/event-stream' });
          const last = Number(req.headers['last-event-id'] ?? 0);
          let seq = last;
          const timer = setInterval(() => {
            seq++;
            res.write(`id: ${seq}\nevent: experiment-event\ndata: {"sequence":${seq}}\n\n`);
            if (seq >= last + 2) {
              clearInterval(timer);
              res.write('event: end\ndata: {"status":"COMPLETED"}\n\n');
              res.end();
            }
          }, 80);
          return;
        }
        if (req.url!.startsWith('/api/experiments/EXP-SLOW/events')) {
          res.writeHead(200, { 'Content-Type': 'text/event-stream' });
          res.write(': open\n\n');
          req.on('close', () => (this.sseClosed = true));
          res.on('close', () => (this.sseClosed = true));
          return;
        }
        if (req.url === '/api/system/info') {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ instanceId: this.name }));
          return;
        }
        if (req.url!.startsWith('/api/experiments') && req.method === 'POST' && body.includes('"threads":999')) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ status: 400, code: 'VALIDATION_FAILED', message: 'threads must be <= 16' }));
          return;
        }
        if (req.url!.startsWith('/api/experiments/EXP-1/export')) {
          res.writeHead(200, { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename="EXP-1.csv"' });
          res.end('a,b\n1,2\n');
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ instance: this.name, method: req.method, url: req.url, body: body ? JSON.parse(body) : null }));
      });
    });
  }

  listen(): Promise<string> {
    return new Promise((resolve) => this.server.listen(0, '127.0.0.1', () => resolve(`http://127.0.0.1:${(this.server.address() as AddressInfo).port}`)));
  }

  close(): Promise<void> {
    this.server.closeAllConnections();
    return new Promise((resolve) => this.server.close(() => resolve()));
  }
}

describe('RaceLab BFF', () => {
  let primary: FakeBackend;
  let secondary: FakeBackend;
  let app: INestApplication;

  beforeAll(async () => {
    primary = new FakeBackend('primary');
    secondary = new FakeBackend('secondary');
    const [p, s] = await Promise.all([primary.listen(), secondary.listen()]);
    app = await createApp({
      port: 0,
      timeoutMs: 2000,
      instances: [
        { instanceId: 'primary', url: p },
        { instanceId: 'secondary', url: s },
      ],
      defaultInstance: 'primary',
      rateLimitPerMinute: 1000,
      bodyLimit: '64kb',
    });
    await app.init();
  });

  afterAll(async () => {
    await app.close();
    await Promise.all([primary.close(), secondary.close()]);
  });

  it('proxies REST calls with body, query and a generated correlation id', async () => {
    const res = await request(app.getHttpServer())
      .post('/api/experiments?x=1')
      .send({ scenarioId: 'bank-withdrawal' })
      .expect(200);
    expect(res.body).toMatchObject({ instance: 'primary', method: 'POST', body: { scenarioId: 'bank-withdrawal' } });
    expect(res.headers['x-correlation-id']).toBeDefined();
    const seen = primary.seen.at(-1)!;
    expect(seen.url).toBe('/api/experiments?x=1');
    expect(seen.headers['x-correlation-id']).toBe(res.headers['x-correlation-id']);
  });

  it('propagates a valid incoming correlation id', async () => {
    const res = await request(app.getHttpServer()).get('/api/catalog').set('X-Correlation-Id', 'abc-123').expect(200);
    expect(res.headers['x-correlation-id']).toBe('abc-123');
    expect(primary.seen.at(-1)!.headers['x-correlation-id']).toBe('abc-123');
  });

  it('relays backend errors unchanged', async () => {
    const res = await request(app.getHttpServer()).post('/api/experiments').send({ configuration: { threads: 999 } }).expect(400);
    expect(res.body.code).toBe('VALIDATION_FAILED');
  });

  it('relays binary exports with content-disposition', async () => {
    const res = await request(app.getHttpServer()).get('/api/experiments/EXP-1/export?format=csv').expect(200);
    expect(res.headers['content-disposition']).toContain('EXP-1.csv');
    expect(res.text).toContain('1,2');
  });

  it('routes to an instance by header or by ?instance= and rejects unknown instances', async () => {
    const byHeader = await request(app.getHttpServer()).get('/api/catalog').set('X-RaceLab-Instance', 'secondary').expect(200);
    expect(byHeader.body.instance).toBe('secondary');
    const byQuery = await request(app.getHttpServer()).get('/api/catalog?instance=secondary').expect(200);
    expect(byQuery.body.instance).toBe('secondary');
    expect(secondary.seen.at(-1)!.url).toBe('/api/catalog');
    const unknown = await request(app.getHttpServer()).get('/api/catalog?instance=nope').expect(400);
    expect(unknown.body.code).toBe('UNKNOWN_INSTANCE');
  });

  it('aggregates the instances', async () => {
    const res = await request(app.getHttpServer()).get('/api/instances').expect(200);
    expect(res.body.map((i: { instanceId: string; available: boolean }) => [i.instanceId, i.available])).toEqual([
      ['primary', true],
      ['secondary', true],
    ]);
  });

  it('streams SSE events individually without buffering', async () => {
    const server = app.getHttpServer();
    await new Promise<void>((resolve, reject) => {
      server.listen(0);
      const port = (server.address() as AddressInfo).port;
      const arrivals: number[] = [];
      let text = '';
      http.get({ port, path: '/api/experiments/EXP-1/events', headers: { accept: 'text/event-stream' } }, (res) => {
        expect(res.headers['content-type']).toContain('text/event-stream');
        expect(res.headers['x-accel-buffering']).toBe('no');
        res.on('data', (c) => {
          arrivals.push(Date.now());
          text += c;
        });
        res.on('end', () => {
          try {
            expect(text).toContain('id: 1');
            expect(text).toContain('id: 2');
            expect(text).toContain('event: end');
            expect(arrivals.length).toBeGreaterThanOrEqual(2); // frames arrived in separate chunks
            resolve();
          } catch (e) {
            reject(e);
          } finally {
            server.close();
          }
        });
      }).on('error', reject);
    });
  });

  it('forwards Last-Event-ID so reconnections resume after the last event', async () => {
    const res = await request(app.getHttpServer())
      .get('/api/experiments/EXP-1/events')
      .set('Accept', 'text/event-stream')
      .set('Last-Event-ID', '7')
      .buffer(true)
      .parse((r, cb) => {
        let data = '';
        r.on('data', (c: Buffer) => (data += c.toString()));
        r.on('end', () => cb(null, data));
      });
    expect(res.body).toContain('id: 8');
    expect(res.body).not.toContain('id: 7\n');
    expect(primary.seen.at(-1)!.headers['last-event-id']).toBe('7');
  });

  it('aborts the upstream stream when the browser disconnects', async () => {
    const server = app.getHttpServer();
    await new Promise<void>((resolve) => server.listen(0, resolve));
    const port = (server.address() as AddressInfo).port;
    await new Promise<void>((resolve) => {
      const req = http.get({ port, path: '/api/experiments/EXP-SLOW/events', headers: { accept: 'text/event-stream' } }, (res) => {
        res.once('data', () => {
          req.destroy();
          resolve();
        });
      });
    });
    for (let i = 0; i < 40 && !primary.sseClosed; i++) await new Promise((r) => setTimeout(r, 50));
    server.close();
    expect(primary.sseClosed).toBe(true);
  });

  it('answers 503 BACKEND_UNAVAILABLE when the engine is down', async () => {
    const down = await createApp({
      port: 0,
      timeoutMs: 1000,
      instances: [{ instanceId: 'primary', url: 'http://127.0.0.1:9' }],
      defaultInstance: 'primary',
      rateLimitPerMinute: 1000,
      bodyLimit: '64kb',
    });
    await down.init();
    const res = await request(down.getHttpServer()).get('/api/catalog').expect(503);
    expect(res.body).toMatchObject({ code: 'BACKEND_UNAVAILABLE', status: 503 });
    expect(res.body.correlationId).toBeDefined();
    await down.close();
  });
});
