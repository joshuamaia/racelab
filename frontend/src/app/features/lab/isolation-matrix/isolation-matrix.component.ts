import { ChangeDetectionStrategy, Component } from '@angular/core';

type Cell = { standard: 'possible' | 'prevented' | 'n/a'; pg: 'possible' | 'prevented' | 'aborts'; note?: string };

interface MatrixRow {
  phenomenon: string;
  cells: [Cell, Cell, Cell, Cell];
}

/** SQL standard vs PostgreSQL actual behaviour (explanatory, static). */
export const ISOLATION_MATRIX: MatrixRow[] = [
  {
    phenomenon: 'Dirty read',
    cells: [
      { standard: 'possible', pg: 'prevented', note: 'PostgreSQL runs READ UNCOMMITTED as READ COMMITTED' },
      { standard: 'prevented', pg: 'prevented' },
      { standard: 'prevented', pg: 'prevented' },
      { standard: 'prevented', pg: 'prevented' },
    ],
  },
  {
    phenomenon: 'Non-repeatable read',
    cells: [
      { standard: 'possible', pg: 'possible' },
      { standard: 'possible', pg: 'possible' },
      { standard: 'prevented', pg: 'prevented', note: 'one snapshot per transaction' },
      { standard: 'prevented', pg: 'prevented' },
    ],
  },
  {
    phenomenon: 'Phantom read',
    cells: [
      { standard: 'possible', pg: 'possible' },
      { standard: 'possible', pg: 'possible' },
      { standard: 'possible', pg: 'prevented', note: 'stricter than the standard: snapshot hides new rows' },
      { standard: 'prevented', pg: 'prevented' },
    ],
  },
  {
    phenomenon: 'Write skew',
    cells: [
      { standard: 'n/a', pg: 'possible' },
      { standard: 'n/a', pg: 'possible' },
      { standard: 'n/a', pg: 'possible', note: 'snapshot isolation allows write skew' },
      { standard: 'prevented', pg: 'aborts', note: 'SSI aborts one tx with SQLSTATE 40001' },
    ],
  },
];

const LOOK = {
  possible: { tone: 'conflict', icon: 'pi pi-exclamation-triangle', label: 'possible' },
  prevented: { tone: 'commit', icon: 'pi pi-shield', label: 'prevented' },
  aborts: { tone: 'wait', icon: 'pi pi-ban', label: 'aborts (40001)' },
  'n/a': { tone: 'neutral', icon: 'pi pi-minus', label: 'not defined' },
} as const;

@Component({
  selector: 'rl-isolation-matrix',
  template: `
    <div class="matrix-wrap">
      <table class="matrix" aria-label="Isolation phenomena: SQL standard versus PostgreSQL">
        <thead>
          <tr>
            <th scope="col">Phenomenon</th>
            @for (l of levels; track l) {
              <th scope="col" class="num">{{ l }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (row of matrix; track row.phenomenon) {
            <tr>
              <th scope="row">{{ row.phenomenon }}</th>
              @for (c of row.cells; track $index) {
                <td>
                  <div class="cell-line"><span class="lbl">SQL std</span><span [class]="'pill tone-' + look[c.standard].tone"><i [class]="look[c.standard].icon" aria-hidden="true"></i>{{ look[c.standard].label }}</span></div>
                  <div class="cell-line"><span class="lbl">PostgreSQL</span><span [class]="'pill tone-' + look[c.pg].tone"><i [class]="look[c.pg].icon" aria-hidden="true"></i>{{ look[c.pg].label }}</span></div>
                  @if (c.note) {
                    <div class="note">{{ c.note }}</div>
                  }
                </td>
              }
            </tr>
          }
        </tbody>
      </table>
    </div>
    <ul class="rl-list small muted notes">
      <li>PostgreSQL accepts <code>READ UNCOMMITTED</code> but treats it as <code>READ COMMITTED</code> — dirty reads never happen.</li>
      <li><code>REPEATABLE READ</code> is snapshot isolation: it prevents phantoms in its snapshot, but still allows write skew.</li>
      <li><code>SERIALIZABLE</code> (SSI) aborts one of the conflicting transactions with SQLSTATE <code>40001</code>; the application must retry.</li>
    </ul>
  `,
  styles: `
    .matrix-wrap { overflow-x: auto; }
    .matrix { border-collapse: collapse; width: 100%; min-width: 46rem; font-size: .8rem; }
    .matrix th, .matrix td { border: 1px solid var(--rl-border); padding: .4rem .5rem; text-align: left; vertical-align: top; }
    .matrix thead th { background: var(--rl-panel-2); font-size: .74rem; }
    .cell-line { display: flex; justify-content: space-between; gap: .4rem; align-items: center; margin-bottom: .2rem; }
    .lbl { color: var(--rl-subtle); font-size: .68rem; }
    .note { font-size: .7rem; color: var(--rl-muted); margin-top: .15rem; }
    .notes { margin-top: .6rem; }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IsolationMatrixComponent {
  protected readonly levels = ['READ UNCOMMITTED', 'READ COMMITTED', 'REPEATABLE READ', 'SERIALIZABLE'];
  protected readonly matrix = ISOLATION_MATRIX;
  protected readonly look = LOOK;
}
