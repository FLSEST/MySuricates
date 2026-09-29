import { describe, it, expect, vi } from 'vitest';
import {
  GOAL_TARGET_ID, liveLine, packageTargetId, parseTarget, structureOf, targetCycleCoordinates, todoTargetId, verdict,
  type MapContext,
} from '../workflowMap';
import { layoutRelations } from '../goalRelationLayout';
import { besideCursor, CURSOR_GAP } from '../workflowMapDrag';
import type { GoalDetail, GoalTodoRelation } from '../../../types';

// 52bc1e8c (Flo): „dass man den Workflow auch ziehen kann“ – Ziele, Urteile, Live-Zeile, Gliederung, Tastatur

const texts = {
  todo: '→ works on: {title}', todoOtherProject: '→ works on: {title} (workflow from {project})', package: '→ work package {title}',
  goal: '→ goal {title}', same: 'Already there', candidate: 'Add it to the goal first', unsupported: 'Later', outside: 'Release to cancel',
};
const ctx = (overrides: Partial<MapContext> = {}): MapContext => ({
  goalTitle: 'Release',
  todos: new Map([
    ['a', { title: 'Licence files', projectId: 'p1', projectName: 'AutoPilot', candidate: false, linkedWorkflowId: null }],
    ['b', { title: 'Banner', projectId: 'p2', projectName: 'Site', candidate: false, linkedWorkflowId: 'wf-fix' }],
    ['c', { title: 'Maybe', projectId: 'p1', projectName: 'AutoPilot', candidate: true, linkedWorkflowId: null }],
  ]),
  packages: new Map([['w1', { title: 'Repo clean' }]]),
  attachSupported: true,
  attachments: [{ id: 'a1', workflowId: 'wf-fix', workPackageId: 'w1', guard: false }],
  ...overrides,
});
const fix = { id: 'wf-fix', name: 'Fix bot', projectId: 'p1', projectName: 'AutoPilot' };

describe('workflow map targets', () => {
  it('reads its drop ids back', () => {
    expect(parseTarget(todoTargetId('a'))).toEqual({ kind: 'todo', todoId: 'a' });
    expect(parseTarget(packageTargetId('w1'))).toEqual({ kind: 'package', workPackageId: 'w1' });
    expect(parseTarget(GOAL_TARGET_ID)).toEqual({ kind: 'goal' });
    expect(parseTarget('row:p1:w1')).toBeNull();
    expect(parseTarget(null)).toBeNull();
  });

  it('judges: a TODO takes any workflow, only once; packages and goal need the newer backend', () => {
    expect(verdict(fix, { kind: 'todo', todoId: 'a' }, ctx())).toBe('ok');
    expect(verdict(fix, { kind: 'todo', todoId: 'b' }, ctx())).toBe('same');
    expect(verdict(fix, { kind: 'todo', todoId: 'c' }, ctx())).toBe('refused');
    expect(verdict(fix, { kind: 'package', workPackageId: 'w1' }, ctx())).toBe('same');
    expect(verdict(fix, { kind: 'goal' }, ctx())).toBe('ok');
    expect(verdict(fix, { kind: 'goal' }, ctx({ attachSupported: false }))).toBe('refused');
    expect(verdict(fix, { kind: 'package', workPackageId: 'gone' }, ctx())).toBe('refused');
  });

  it('says where the workflow lands – naming the project when it comes from another one – or why not', () => {
    expect(liveLine(fix, { kind: 'todo', todoId: 'a' }, ctx(), texts)).toEqual({ text: '→ works on: Licence files', refused: false });
    const site = { ...fix, id: 'wf-site', projectId: 'p2', projectName: 'Site' };
    expect(liveLine(site, { kind: 'todo', todoId: 'a' }, ctx(), texts).text).toBe('→ works on: Licence files (workflow from Site)');
    expect(liveLine(site, { kind: 'package', workPackageId: 'w1' }, ctx(), texts).text).toBe('→ work package Repo clean');
    expect(liveLine(site, { kind: 'goal' }, ctx(), texts).text).toBe('→ goal Release');
    expect(liveLine(fix, { kind: 'todo', todoId: 'b' }, ctx(), texts)).toEqual({ text: 'Already there', refused: true });
    expect(liveLine(fix, { kind: 'todo', todoId: 'c' }, ctx(), texts)).toEqual({ text: 'Add it to the goal first', refused: true });
    expect(liveLine(site, { kind: 'goal' }, ctx({ attachSupported: false }), texts)).toEqual({ text: 'Later', refused: true });
    expect(liveLine(fix, null, ctx(), texts)).toEqual({ text: 'Release to cancel', refused: true });
  });
});

describe('the map by work package', () => {
  const relation = (id: string, projectId: string, projectName: string): GoalTodoRelation => ({
    id, title: id, status: 'OPEN', priority: 'MEDIUM', projectId, projectName, tags: [], goalRank: null, candidate: false,
    origin: { kind: 'USER', workflowId: null, workflowName: null, runId: null, runStatus: null, findingIds: [], agent: null },
    workers: [], result: null,
  });

  it('groups project → package in the goal\'s order, "no work package" last per project', () => {
    const detail = {
      projects: [{ projectId: 'p2', position: 0 }, { projectId: 'p1', position: 1 }],
      workPackages: [
        { id: 'w-b', title: 'Second', projectId: 'p1', position: 1 },
        { id: 'w-a', title: 'First', projectId: 'p1', position: 0 },
        { id: 'w-s', title: 'Site pack', projectId: 'p2', position: 0 },
      ],
      todos: [{ id: 'a', workPackageId: 'w-b' }, { id: 'b', workPackageId: 'w-a' }, { id: 'c', workPackageId: null }, { id: 'd', workPackageId: 'w-s' }],
    } as unknown as GoalDetail;
    const layout = layoutRelations([
      relation('a', 'p1', 'AutoPilot'), relation('b', 'p1', 'AutoPilot'), relation('c', 'p1', 'AutoPilot'), relation('d', 'p2', 'Site'),
    ], structureOf(detail));
    expect(layout.groups.map((group) => [group.label, group.workPackageId, group.packageTitle])).toEqual([
      ['Site', 'w-s', 'Site pack'],
      ['AutoPilot', 'w-a', 'First'],
      ['AutoPilot', 'w-b', 'Second'],
      ['AutoPilot', null, null],
    ]);
    // each group reaches down to the next – that is its drop area
    layout.groups.slice(0, -1).forEach((group, index) => expect(group.y + group.h!).toBe(layout.groups[index + 1]!.y));
  });
});

describe('keyboard: the arrows jump from target to target', () => {
  const rect = (top: number, left = 0) => ({ top, left, width: 100, height: 20, bottom: top + 20, right: left + 100 });
  const containers = [
    { id: todoTargetId('b'), disabled: false }, { id: GOAL_TARGET_ID, disabled: false }, { id: todoTargetId('a'), disabled: false },
    { id: 'row:x', disabled: false },
  ];
  const context = (overId: string | null) => ({
    active: { id: 'wm-wf:x' }, collisionRect: { width: 40, height: 10 }, over: overId ? { id: overId } : null,
    droppableRects: new Map<string, ReturnType<typeof rect>>([
      [GOAL_TARGET_ID, rect(0)], [todoTargetId('a'), rect(100)], [todoTargetId('b'), rect(200)], ['row:x', rect(50)],
    ]),
    droppableContainers: { getEnabled: () => containers },
  });
  const press = (code: string, overId: string | null) => targetCycleCoordinates(
    { code, preventDefault: vi.fn() } as unknown as KeyboardEvent,
    { context: context(overId), active: 'wm-wf:x', currentCoordinates: { x: 0, y: 0 } } as never,
  );

  it('goes down the targets in map order, wraps around, and ignores surfaces that are no targets', () => {
    // centre of the target minus half the dragged item
    expect(press('ArrowDown', null)).toEqual({ x: 30, y: 5 });
    expect(press('ArrowDown', GOAL_TARGET_ID)).toEqual({ x: 30, y: 105 });
    expect(press('ArrowRight', todoTargetId('a'))).toEqual({ x: 30, y: 205 });
    expect(press('ArrowDown', todoTargetId('b'))).toEqual({ x: 30, y: 5 });
    expect(press('ArrowUp', GOAL_TARGET_ID)).toEqual({ x: 30, y: 205 });
    expect(press('KeyA', null)).toBeUndefined();
  });
});

describe('the dragged workflow sits beside the pointer', () => {
  const rect = { top: 100, left: 1100, width: 256, height: 40, right: 1356, bottom: 140 };
  const args = (activatorEvent: Event | null) => ({
    activatorEvent, draggingNodeRect: rect, transform: { x: -500, y: 200, scaleX: 1, scaleY: 1 },
    active: null, activeNodeRect: rect, containerNodeRect: null, over: null, overlayNodeRect: null, scrollableAncestors: [], scrollableAncestorRects: [], windowRect: null,
  });

  it('puts its corner just right of and below the pointer, so the target stays visible', () => {
    // gegriffen bei (1230, 120): die Karte beginnt 16/14 px rechts unterhalb des Zeigers, der 500 px nach links gewandert ist
    const moved = besideCursor(args(new MouseEvent('pointerdown', { clientX: 1230, clientY: 120 })) as never);
    expect(moved).toMatchObject({ x: -500 + 130 + CURSOR_GAP.x, y: 200 + 20 + CURSOR_GAP.y });
  });

  it('leaves a keyboard drag where the arrows put it', () => {
    expect(besideCursor(args(new KeyboardEvent('keydown', { code: 'Space' })) as never)).toMatchObject({ x: -500, y: 200 });
  });
});
