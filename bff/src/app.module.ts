import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common';
import { APP_FILTER, APP_GUARD } from '@nestjs/core';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { BackendClient } from './backend.client';
import { BFF_CONFIG, BffConfig, loadConfig } from './config';
import { CorrelationIdMiddleware } from './correlation.middleware';
import { GlobalExceptionFilter } from './http-exception.filter';
import { ProxyController } from './proxy.controller';
import { SseController } from './sse.controller';

@Module({})
export class AppModule implements NestModule {
  static register(config: BffConfig = loadConfig()) {
    return {
      module: AppModule,
      imports: [ThrottlerModule.forRoot([{ ttl: 60_000, limit: config.rateLimitPerMinute }])],
      // SseController first: its specific route must win over the generic proxy
      controllers: [SseController, ProxyController],
      providers: [
        { provide: BFF_CONFIG, useValue: config },
        BackendClient,
        { provide: APP_GUARD, useClass: ThrottlerGuard },
        { provide: APP_FILTER, useClass: GlobalExceptionFilter },
      ],
    };
  }

  configure(consumer: MiddlewareConsumer): void {
    consumer.apply(CorrelationIdMiddleware).forRoutes('*');
  }
}
