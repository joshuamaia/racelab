import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import type * as Monaco from 'monaco-editor';
import { SkeletonModule } from 'primeng/skeleton';
import { MonacoLoaderService, monacoLanguage } from './monaco-loader.service';

/** Read-only Monaco diff editor: original (left) vs modified (right). */
@Component({
  selector: 'rl-monaco-diff',
  imports: [SkeletonModule],
  template: `
    <div class="monaco-wrap" [style.height]="height()">
      @if (!ready() && !failed()) {
        <p-skeleton width="100%" height="100%" />
      }
      @if (failed()) {
        <div class="fallback-grid">
          <pre class="code-block">{{ original() }}</pre>
          <pre class="code-block">{{ modified() }}</pre>
        </div>
      }
      <div #host class="monaco-host" [class.hidden]="!ready()" aria-label="Diff between baseline and selected strategy"></div>
    </div>
  `,
  styles: `
    :host { display: block; }
    .monaco-wrap { position: relative; }
    .monaco-host { position: absolute; inset: 0; }
    .monaco-host.hidden { visibility: hidden; }
    .fallback-grid { display: grid; grid-template-columns: 1fr 1fr; gap: .5rem; height: 100%; }
    .fallback-grid pre { max-height: none; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MonacoDiffComponent {
  readonly original = input('');
  readonly modified = input('');
  readonly language = input<string | null>('plaintext');
  readonly height = input('480px');
  readonly sideBySide = input(true);

  protected readonly ready = signal(false);
  protected readonly failed = signal(false);
  private readonly host = viewChild.required<ElementRef<HTMLElement>>('host');
  private readonly loader = inject(MonacoLoaderService);
  private editor: Monaco.editor.IStandaloneDiffEditor | null = null;
  private monaco: typeof Monaco | null = null;
  private models: Monaco.editor.ITextModel[] = [];

  constructor() {
    afterNextRender(() => {
      this.loader
        .load()
        .then((monaco) => {
          this.monaco = monaco;
          this.editor = monaco.editor.createDiffEditor(this.host().nativeElement, {
            readOnly: true,
            originalEditable: false,
            automaticLayout: true,
            renderSideBySide: this.sideBySide(),
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            fontFamily: "'JetBrains Mono', ui-monospace, Consolas, monospace",
            fontSize: 13,
            theme: this.loader.themeName,
          });
          this.setModels();
          this.ready.set(true);
        })
        .catch(() => this.failed.set(true));
    });

    effect(() => {
      this.original();
      this.modified();
      this.language();
      if (this.editor) this.setModels();
    });
    effect(() => {
      const sbs = this.sideBySide();
      this.editor?.updateOptions({ renderSideBySide: sbs });
    });

    inject(DestroyRef).onDestroy(() => {
      this.editor?.dispose();
      this.models.forEach((m) => m.dispose());
    });
  }

  private setModels(): void {
    if (!this.monaco || !this.editor) return;
    const lang = monacoLanguage(this.language());
    const original = this.monaco.editor.createModel(this.original(), lang);
    const modified = this.monaco.editor.createModel(this.modified(), lang);
    this.editor.setModel({ original, modified });
    this.models.forEach((m) => m.dispose());
    this.models = [original, modified];
  }
}
