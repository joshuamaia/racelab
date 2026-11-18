import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, of } from 'rxjs';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { SkeletonModule } from 'primeng/skeleton';
import { TabsModule } from 'primeng/tabs';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TooltipModule } from 'primeng/tooltip';
import { RacelabApi } from '../../core/api/racelab-api.service';
import { CodeExample, CodeSnippet, StrategyType } from '../../core/models/api.models';
import { CatalogService } from '../../core/state/catalog.service';
import { copyToClipboard, downloadText } from '../../core/util/download';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { MonacoDiffComponent } from '../../shared/monaco/monaco-diff.component';
import { MonacoEditorComponent } from '../../shared/monaco/monaco-editor.component';

export type CodeTab = 'java' | 'sql' | 'typescript' | 'configuration' | 'explanation';

const EXTENSIONS: Record<string, string> = { java: 'java', sql: 'sql', typescript: 'ts', json: 'json', yaml: 'yml' };

@Component({
  selector: 'rl-code-explorer',
  imports: [
    FormsModule,
    ButtonModule,
    SelectModule,
    SkeletonModule,
    TabsModule,
    ToggleSwitchModule,
    TooltipModule,
    EmptyStateComponent,
    MonacoDiffComponent,
    MonacoEditorComponent,
  ],
  templateUrl: './code-explorer.component.html',
  styleUrl: './code-explorer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CodeExplorerComponent implements OnInit {
  private readonly api = inject(RacelabApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly messages = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly catalog = inject(CatalogService);

  protected readonly scenarioId = signal<string | null>(null);
  protected readonly strategy = signal<StrategyType | null>(null);
  protected readonly example = signal<CodeExample | null>(null);
  protected readonly loading = signal(false);
  protected readonly notFound = signal(false);
  protected readonly tab = signal<CodeTab>('java');
  protected readonly snippetIndex = signal(0);
  protected readonly diffView = signal(false);

  protected readonly scenarioOptions = computed(() =>
    this.catalog.scenarios().map((s) => ({ label: `${s.name} (${s.category.toLowerCase()})`, value: s.id })),
  );
  protected readonly scenario = computed(() => this.catalog.scenario(this.scenarioId()));
  protected readonly strategyOptions = computed(() =>
    (this.scenario()?.supportedStrategies ?? []).map((s) => ({ label: this.catalog.strategyName(s), value: s })),
  );
  protected readonly strategyInfo = computed(() => this.catalog.strategy(this.strategy()));

  /** Snippets of the selected strategy for the current language tab. */
  protected readonly snippets = computed<CodeSnippet[]>(() => {
    const c = this.example();
    const t = this.tab();
    if (!c) return [];
    return t === 'java' ? c.java : t === 'sql' ? c.sql : [];
  });
  protected readonly baselineSnippets = computed<CodeSnippet[]>(() => {
    const c = this.example();
    const t = this.tab();
    if (!c?.baseline) return [];
    return t === 'java' ? c.baseline.java : t === 'sql' ? c.baseline.sql : [];
  });
  protected readonly snippetOptions = computed(() => this.snippets().map((s, i) => ({ label: s.title, value: i })));
  protected readonly current = computed(() => this.snippets()[this.snippetIndex()] ?? null);
  protected readonly baseline = computed(() => {
    const list = this.baselineSnippets();
    return list[this.snippetIndex()] ?? list[0] ?? null;
  });
  protected readonly isBaselineStrategy = computed(() => this.strategy() === 'NONE' || this.example()?.baseline?.strategy === this.strategy());
  protected readonly configJson = computed(() => {
    const cfg = this.example()?.configuration;
    return cfg ? JSON.stringify(cfg, null, 2) : '';
  });

  ngOnInit(): void {
    const qp = this.route.snapshot.queryParamMap;
    const qpScenario = qp.get('scenarioId');
    const qpStrategy = qp.get('strategy') as StrategyType | null;
    this.catalog
      .ensureLoaded()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        const sc = qpScenario && this.catalog.scenario(qpScenario) ? qpScenario : this.catalog.scenarios()[0]?.id ?? null;
        if (!sc) return;
        const supported = this.catalog.scenario(sc)?.supportedStrategies ?? [];
        const st = qpStrategy && supported.includes(qpStrategy) ? qpStrategy : this.pickDefaultStrategy(sc);
        this.select(sc, st);
      });
  }

  protected onScenarioChange(id: string): void {
    this.select(id, this.pickDefaultStrategy(id));
  }

  protected onStrategyChange(s: StrategyType): void {
    const sc = this.scenarioId();
    if (sc) this.select(sc, s);
  }

  protected onTabChange(t: CodeTab): void {
    this.tab.set(t);
    this.snippetIndex.set(0);
  }

  protected async copy(text: string | null | undefined): Promise<void> {
    if (!text) return;
    const ok = await copyToClipboard(text);
    this.messages.add(
      ok
        ? { severity: 'success', summary: 'Copied to clipboard', life: 2000 }
        : { severity: 'warn', summary: 'Clipboard not available', detail: 'Select the code and copy it manually.', life: 4000 },
    );
  }

  protected download(snippet: CodeSnippet | null): void {
    if (!snippet) return;
    const ext = EXTENSIONS[snippet.language.toLowerCase()] ?? 'txt';
    const base = (snippet.source && snippet.source !== 'generated' ? snippet.source.replace(/\.[^.]+$/, '') : snippet.title)
      .replace(/[^a-zA-Z0-9_-]+/g, '-')
      .replace(/^-+|-+$/g, '');
    downloadText(snippet.code, `${base || 'snippet'}-${this.strategy() ?? 'code'}.${ext}`);
  }

  protected downloadConfig(): void {
    const json = this.configJson();
    if (json) downloadText(json, `${this.scenarioId() ?? 'scenario'}-configuration.json`, 'application/json');
  }

  private pickDefaultStrategy(scenarioId: string): StrategyType | null {
    const s = this.catalog.scenario(scenarioId);
    if (!s) return null;
    return s.supportedStrategies.find((x) => x !== 'NONE') ?? s.defaultStrategy ?? null;
  }

  private select(scenarioId: string, strategy: StrategyType | null): void {
    this.scenarioId.set(scenarioId);
    this.strategy.set(strategy);
    this.snippetIndex.set(0);
    void this.router.navigate([], {
      queryParams: { scenarioId, strategy },
      replaceUrl: true,
      queryParamsHandling: 'merge',
    });
    if (!strategy) {
      this.example.set(null);
      return;
    }
    this.loading.set(true);
    this.notFound.set(false);
    this.api
      .codeExample(scenarioId, strategy, { silent: true })
      .pipe(
        catchError(() => of(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((c) => {
        this.example.set(c);
        this.notFound.set(!c);
        this.loading.set(false);
      });
  }
}
