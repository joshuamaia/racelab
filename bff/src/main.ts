import 'reflect-metadata';
import { INestApplication } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import { NestExpressApplication } from '@nestjs/platform-express';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { json } from 'express';
import { AppModule } from './app.module';
import { BffConfig, loadConfig } from './config';

export async function createApp(config: BffConfig = loadConfig()): Promise<INestApplication> {
  const app = await NestFactory.create<NestExpressApplication>(AppModule.register(config), {
    bodyParser: false,
    logger: ['error', 'warn', 'log'],
  });
  app.use(json({ limit: config.bodyLimit }));
  app.set('trust proxy', 'loopback, linklocal, uniquelocal');
  app.disable('x-powered-by');
  app.enableShutdownHooks();
  const doc = new DocumentBuilder()
    .setTitle('RaceLab BFF')
    .setDescription(
      'Backend-for-frontend of RaceLab. Proxies /api/** (REST and SSE) to the Spring Boot experiment engine, ' +
        'propagates X-Correlation-Id and routes to a backend instance with X-RaceLab-Instance or ?instance=. ' +
        'The experiment API itself is documented by the backend at /api/swagger-ui.html.',
    )
    .setVersion('1.0.0')
    .build();
  SwaggerModule.setup('bff/docs', app, SwaggerModule.createDocument(app, doc));
  return app;
}

async function bootstrap(): Promise<void> {
  const config = loadConfig();
  const app = await createApp(config);
  await app.listen(config.port, '0.0.0.0');
  process.stdout.write(
    JSON.stringify({ level: 'info', msg: `RaceLab BFF listening on ${config.port}`, instances: config.instances }) + '\n',
  );
}

if (require.main === module) {
  void bootstrap();
}
