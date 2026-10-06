import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import BpmnDiagramView from '../BpmnDiagramView';
import { useI18nStore } from '../../../i18n';

afterEach(() => useI18nStore.setState({ locale: 'de' }));

const SAMPLE_XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
             id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <process id="p1" name="Demo" isExecutable="true">
    <startEvent id="s1" name="Start" />
    <scriptTask id="t1" name="Maven Tests ausführen" />
    <exclusiveGateway id="g1" name="OK?" />
    <endEvent id="e1" name="Ende" />
    <sequenceFlow id="f1" sourceRef="s1" targetRef="t1" />
    <sequenceFlow id="f2" sourceRef="t1" targetRef="g1" name="ja" />
    <sequenceFlow id="f3" sourceRef="g1" targetRef="e1" />
  </process>
  <bpmndi:BPMNDiagram id="d1">
    <bpmndi:BPMNPlane id="pl1" bpmnElement="p1">
      <bpmndi:BPMNShape id="sh1" bpmnElement="s1"><dc:Bounds x="100" y="182" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh2" bpmnElement="t1"><dc:Bounds x="286" y="160" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh3" bpmnElement="g1"><dc:Bounds x="536" y="175" width="50" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh4" bpmnElement="e1"><dc:Bounds x="736" y="182" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed1" bpmnElement="f1"><di:waypoint x="136" y="200"/><di:waypoint x="286" y="200"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed2" bpmnElement="f2"><di:waypoint x="386" y="200"/><di:waypoint x="536" y="200"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed3" bpmnElement="f3"><di:waypoint x="586" y="200"/><di:waypoint x="736" y="200"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`;

/** Excerpt of a real export: "report" processes {{ block.tests.output }}. */
const DATA_FLOW_XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
             id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <process id="p1" name="Datenfluss" isExecutable="false">
    <scriptTask id="b_0_tests" name="tests">
      <dataOutputAssociation id="dataflow_out_0"><targetRef>dataref_tests</targetRef></dataOutputAssociation>
    </scriptTask>
    <serviceTask id="b_1_report" name="report">
      <property id="b_1_report_data_input" name="__targetRef_placeholder"/>
      <dataInputAssociation id="dataflow_in_0">
        <sourceRef>dataref_tests</sourceRef>
        <targetRef>b_1_report_data_input</targetRef>
      </dataInputAssociation>
    </serviceTask>
    <sequenceFlow id="f1" sourceRef="b_0_tests" targetRef="b_1_report" />
    <dataObject id="data_tests"/>
    <dataObjectReference id="dataref_tests" dataObjectRef="data_tests" name="Ausgabe: tests"/>
  </process>
  <bpmndi:BPMNDiagram id="d1">
    <bpmndi:BPMNPlane id="pl1" bpmnElement="p1">
      <bpmndi:BPMNShape id="sh1" bpmnElement="b_0_tests"><dc:Bounds x="286" y="160" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh2" bpmnElement="b_1_report"><dc:Bounds x="536" y="160" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh3" bpmnElement="dataref_tests"><dc:Bounds x="318" y="300" width="36" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed1" bpmnElement="f1"><di:waypoint x="386" y="200"/><di:waypoint x="536" y="200"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed2" bpmnElement="dataflow_out_0"><di:waypoint x="336" y="240"/><di:waypoint x="336" y="300"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed3" bpmnElement="dataflow_in_0"><di:waypoint x="354" y="325"/><di:waypoint x="586" y="240"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`;

/** foreach loop with the geometry of the backend export (BpmnModelBuilder: SUB_PAD 40, GAP 150). */
const FOREACH_XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
             id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
  <process id="p1" name="Schleife" isExecutable="false">
    <startEvent id="start" name="Start" />
    <subProcess id="b_0_loop" name="Schleife: items">
      <multiInstanceLoopCharacteristics />
      <startEvent id="b_0_body_start" />
      <scriptTask id="b_0_body" name="pro Element" />
      <endEvent id="b_0_body_end" />
      <sequenceFlow id="fi1" sourceRef="b_0_body_start" targetRef="b_0_body" />
      <sequenceFlow id="fi2" sourceRef="b_0_body" targetRef="b_0_body_end" />
    </subProcess>
    <endEvent id="end" name="Ende" />
    <sequenceFlow id="f1" sourceRef="start" targetRef="b_0_loop" />
    <sequenceFlow id="f2" sourceRef="b_0_loop" targetRef="end" />
  </process>
  <bpmndi:BPMNDiagram id="d1">
    <bpmndi:BPMNPlane id="pl1" bpmnElement="p1">
      <bpmndi:BPMNShape id="sh1" bpmnElement="start"><dc:Bounds x="100" y="182" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh2" bpmnElement="b_0_loop" isExpanded="true"><dc:Bounds x="286" y="120" width="552" height="160"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh3" bpmnElement="b_0_body_start"><dc:Bounds x="326" y="182" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh4" bpmnElement="b_0_body"><dc:Bounds x="512" y="160" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh5" bpmnElement="b_0_body_end"><dc:Bounds x="762" y="182" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh6" bpmnElement="end"><dc:Bounds x="988" y="182" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed1" bpmnElement="f1"><di:waypoint x="136" y="200"/><di:waypoint x="286" y="200"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed2" bpmnElement="fi1"><di:waypoint x="362" y="200"/><di:waypoint x="512" y="200"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed3" bpmnElement="fi2"><di:waypoint x="612" y="200"/><di:waypoint x="762" y="200"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed4" bpmnElement="f2"><di:waypoint x="838" y="200"/><di:waypoint x="988" y="200"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`;

describe('BpmnDiagramView loop subprocess', () => {
  const box = (el: Element) => {
    if (el.tagName === 'circle') {
      const [cx, cy, r] = ['cx', 'cy', 'r'].map((a) => Number(el.getAttribute(a)));
      return { x: cx! - r!, y: cy! - r!, w: 2 * r!, h: 2 * r! };
    }
    const [x, y, w, h] = ['x', 'y', 'width', 'height'].map((a) => Number(el.getAttribute(a)));
    return { x: x!, y: y!, w: w!, h: h! };
  };
  const inside = (inner: ReturnType<typeof box>, outer: ReturnType<typeof box>) =>
    inner.x >= outer.x && inner.y >= outer.y && inner.x + inner.w <= outer.x + outer.w && inner.y + inner.h <= outer.y + outer.h;

  it.each(['horizontal', 'vertical'] as const)('legt Start, Inhalt und Ende der Schleife in ihren Kasten (%s)', (orientation) => {
    const { container } = render(<BpmnDiagramView xml={FOREACH_XML} orientation={orientation} />);
    const frame = box(container.querySelector('rect[rx="12"]')!);
    const circles = Array.from(container.querySelectorAll('circle')).map(box);
    const task = box(container.querySelector('rect[rx="10"]')!);

    // Inside: start and end of the loop plus its body; outside: start and end of the workflow.
    expect(circles.filter((c) => inside(c, frame))).toHaveLength(2);
    expect(circles.filter((c) => !inside(c, frame))).toHaveLength(2);
    expect(inside(task, frame)).toBe(true);
  });

  it('enters the box at the top and leaves it at the bottom in the vertical layout', () => {
    const { container } = render(<BpmnDiagramView xml={FOREACH_XML} orientation="vertical" />);
    const frame = box(container.querySelector('rect[rx="12"]')!);
    const circles = Array.from(container.querySelectorAll('circle')).map(box).sort((a, b) => a.y - b.y);
    // Order from top to bottom: start, [loop start, loop end], end.
    expect(circles[0]!.y + circles[0]!.h).toBeLessThanOrEqual(frame.y);
    expect(circles[3]!.y).toBeGreaterThanOrEqual(frame.y + frame.h);
  });
});

describe('BpmnDiagramView data flow', () => {
  it('draws the data object and two dashed associations', () => {
    useI18nStore.setState({ locale: 'de' });
    const { container } = render(<BpmnDiagramView xml={DATA_FLOW_XML} />);
    // Data object: rectangle with a folded corner
    expect(container.querySelectorAll('path[data-bpmn="data-object"]')).toHaveLength(1);
    expect(screen.getByText('Ausgabe: tests')).toBeInTheDocument();
    // Associations dashed, control flow solid
    const dashed = Array.from(container.querySelectorAll('polyline')).filter((p) =>
      p.hasAttribute('stroke-dasharray'),
    );
    expect(dashed).toHaveLength(2);
    expect(container.querySelectorAll('polyline')).toHaveLength(3);
  });

  it('localizes the data object label (EN)', () => {
    useI18nStore.setState({ locale: 'en' });
    render(<BpmnDiagramView xml={DATA_FLOW_XML} />);
    expect(screen.getByText('Output: tests')).toBeInTheDocument();
  });

  it('wraps long data object names and truncates them like task labels', () => {
    useI18nStore.setState({ locale: 'de' });
    const langer = DATA_FLOW_XML.replace(
      'name="Ausgabe: tests"',
      'name="Ausgabe: Vollstaendiger Integrationstestlauf inklusive Abdeckungsbericht und Fehleranalyse"',
    );
    const { container } = render(<BpmnDiagramView xml={langer} />);
    const gruppe = container.querySelector('path[data-bpmn="data-object"]')!.parentElement!;
    const tspans = Array.from(gruppe.querySelectorAll('tspan'));
    expect(tspans.length).toBeGreaterThan(1);
    expect(tspans.length).toBeLessThanOrEqual(3);
    expect(tspans.some((t) => t.textContent?.endsWith('…'))).toBe(true);
    // full name stays in the group's tooltip
    const titles = Array.from(container.querySelectorAll('title')).map((t) => t.textContent);
    expect(titles.some((t) => t?.includes('Abdeckungsbericht und Fehleranalyse'))).toBe(true);
  });

  it('does not make the data object clickable', () => {
    const onSelectBlock = vi.fn();
    const { container } = render(<BpmnDiagramView xml={DATA_FLOW_XML} onSelectBlock={onSelectBlock} />);
    // only the two tasks carry a block ID (b_<i>_…), the data object does not
    expect(container.querySelectorAll('g[role="button"]')).toHaveLength(2);
  });

  it('routes the associations to the boxes involved in the vertical layout too', () => {
    const { container } = render(<BpmnDiagramView xml={DATA_FLOW_XML} orientation="vertical" />);
    const dashed = Array.from(container.querySelectorAll('polyline')).filter((p) =>
      p.hasAttribute('stroke-dasharray'),
    );
    expect(dashed).toHaveLength(2);
    // Re-routing instead of mirrored old points: no edge keeps the original coordinates
    expect(dashed.some((p) => (p.getAttribute('points') || '').includes('336,240'))).toBe(false);
  });

  it('places the data object below its producer in the vertical layout, not beside it', () => {
    const { container } = render(<BpmnDiagramView xml={DATA_FLOW_XML} orientation="vertical" />);
    // Producer is the first task (b_0_tests), the data object is the symbol with the folded corner
    const erzeuger = container.querySelectorAll('rect')[0]!;
    const oben = Number(erzeuger.getAttribute('y'));
    const unten = oben + Number(erzeuger.getAttribute('height'));
    const d = container.querySelector('path[data-bpmn="data-object"]')!.getAttribute('d')!;
    const [dataX, dataY] = d.slice(2).split(' ').map(Number) as [number, number];
    // below (downwards in the flow), not at the same height to the right
    expect(dataY).toBeGreaterThanOrEqual(unten);
    // offset sideways so that it does not block the flow column
    expect(dataX).toBeGreaterThan(Number(erzeuger.getAttribute('x')));
  });

  it('routes the output association without a hook in the vertical layout', () => {
    const { container } = render(<BpmnDiagramView xml={DATA_FLOW_XML} orientation="vertical" />);
    const dashed = Array.from(container.querySelectorAll('polyline')).filter((p) =>
      p.hasAttribute('stroke-dasharray'),
    );
    // Both associations run monotonically in flow direction (y never backwards) — no hook
    for (const kante of dashed) {
      const ys = (kante.getAttribute('points') || '').trim().split(/\s+/).map((pt) => Number(pt.split(',')[1]));
      for (let i = 1; i < ys.length; i++) {
        expect(ys[i]!).toBeGreaterThanOrEqual(ys[i - 1]! - 0.001);
      }
    }
  });
});

describe('BpmnDiagramView', () => {
  it('renders all shapes and edges from the BPMN DI', () => {
    const { container } = render(<BpmnDiagramView xml={SAMPLE_XML} />);
    expect(container.querySelectorAll('svg')).toHaveLength(1);
    expect(container.querySelectorAll('circle')).toHaveLength(2); // start + end
    expect(container.querySelectorAll('rect')).toHaveLength(1); // Task
    expect(container.querySelectorAll('polygon')).toHaveLength(1); // Gateway
    expect(container.querySelectorAll('polyline')).toHaveLength(3); // edges
    expect(screen.getByText('Maven Tests ausführen')).toBeInTheDocument();
    expect(screen.getByText('ja')).toBeInTheDocument(); // edge label
  });

  it('shows a message for invalid XML', () => {
    render(<BpmnDiagramView xml="kein xml" />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('shows its own message for well-formed BPMN without layout data', () => {
    const noDi = `<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
      <process id="p"><startEvent id="s"/></process></definitions>`;
    render(<BpmnDiagramView xml={noDi} />);
    // different message than for broken XML
    expect(screen.getByRole('alert')).toHaveTextContent(/kein Diagramm|no diagram/i);
  });

  it('transposes the layout for vertical orientation', () => {
    const { container, rerender } = render(<BpmnDiagramView xml={SAMPLE_XML} orientation="horizontal" />);
    const h = container.querySelector('svg')!.getAttribute('viewBox')!.split(' ').map(Number);
    rerender(<BpmnDiagramView xml={SAMPLE_XML} orientation="vertical" />);
    const v = container.querySelector('svg')!.getAttribute('viewBox')!.split(' ').map(Number);
    expect(h[2]!).toBeGreaterThan(h[3]!); // horizontal: wider than tall
    expect(v[3]!).toBeGreaterThan(v[2]!); // vertical: taller than wide
    // Compression: the vertical height is clearly smaller than the horizontal width
    // (without compaction it would be ~equal because of the transposition). 0.85 leaves room for padding.
    expect(v[3]!).toBeLessThan(h[2]! * 0.85);
  });

  it('attaches arrows to the box centers in the vertical layout', () => {
    const { container } = render(<BpmnDiagramView xml={SAMPLE_XML} orientation="vertical" />);
    const rect = container.querySelector('rect')!; // task box
    const cx = Number(rect.getAttribute('x')) + Number(rect.getAttribute('width')) / 2;
    const polyXs = Array.from(container.querySelectorAll('polyline')).flatMap((p) =>
      (p.getAttribute('points') || '').trim().split(/\s+/).map((pt) => Number(pt.split(',')[0])),
    );
    // Re-routing: at least one edge attaches exactly to the task center (previously ~10px off)
    expect(polyXs.some((x) => Math.abs(x - cx) < 0.5)).toBe(true);
  });

  it('localizes the German type prefix of unnamed boxes (EN)', () => {
    useI18nStore.setState({ locale: 'en' });
    const nameless = SAMPLE_XML.replace('name="Maven Tests ausführen"', 'name="KI: git status"');
    render(<BpmnDiagramView xml={nameless} />);
    // Prefix "KI:" becomes "AI:", the command "git status" stays unchanged
    expect(screen.getByText('AI: git status')).toBeInTheDocument();
    expect(screen.queryByText('KI: git status')).toBeNull();
  });

  it('leaves block names untranslated (only prefixes are localized)', () => {
    useI18nStore.setState({ locale: 'en' });
    render(<BpmnDiagramView xml={SAMPLE_XML} />);
    // "Maven Tests ausführen" is a user name without a type prefix → unchanged in EN
    expect(screen.getByText('Maven Tests ausführen')).toBeInTheDocument();
  });

  it('reports the block index when a box is clicked', () => {
    // scriptTask ID carries the block index (b_2_…) as in the real export
    const clickableXml = SAMPLE_XML.replace('id="t1"', 'id="b_2_maven"').replace('bpmnElement="t1"', 'bpmnElement="b_2_maven"');
    const onSelectBlock = vi.fn();
    const { container } = render(<BpmnDiagramView xml={clickableXml} onSelectBlock={onSelectBlock} />);
    const clickable = container.querySelector('g[role="button"]');
    expect(clickable).not.toBeNull();
    fireEvent.click(clickable!);
    expect(onSelectBlock).toHaveBeenCalledWith({ blockIndex: 2 });
  });

  it('reports block and case index for a nested switch-case box', () => {
    // Switch case child carries the path in the ID: b_<block>_c<case>_…
    const caseXml = SAMPLE_XML.replace('id="t1"', 'id="b_3_c1_0_alarm"').replace('bpmnElement="t1"', 'bpmnElement="b_3_c1_0_alarm"');
    const onSelectBlock = vi.fn();
    const { container } = render(<BpmnDiagramView xml={caseXml} onSelectBlock={onSelectBlock} />);
    fireEvent.click(container.querySelector('g[role="button"]')!);
    expect(onSelectBlock).toHaveBeenCalledWith({ blockIndex: 3, sub: { kind: 'case', caseIndex: 1 } });
  });

  it('truncates labels that are too long with an ellipsis and keeps them in the box', () => {
    const longXml = SAMPLE_XML.replace(
      'name="Maven Tests ausführen"',
      'name="Vollautomatische Integrationstestsuite mit ausführlicher Fehleranalyse und Reporting"',
    );
    const { container } = render(<BpmnDiagramView xml={longXml} />);
    const tspans = Array.from(container.querySelectorAll('tspan'));
    // max. 3 lines, the last one ends with an ellipsis
    expect(tspans.length).toBeLessThanOrEqual(3);
    expect(tspans.some((t) => t.textContent?.endsWith('…'))).toBe(true);
    // full name is kept as a tooltip
    const titles = Array.from(container.querySelectorAll('title')).map((t) => t.textContent);
    expect(titles).toContain('Vollautomatische Integrationstestsuite mit ausführlicher Fehleranalyse und Reporting');
  });

  it('flashes only the box of the requested block and scrolls it into view', () => {
    const flashXml = SAMPLE_XML.replace('id="t1"', 'id="b_2_maven"').replace('bpmnElement="t1"', 'bpmnElement="b_2_maven"');
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const { container, rerender } = render(<BpmnDiagramView xml={flashXml} flashBlock={{ index: 1, nonce: 1 }} />);
    expect(container.querySelector('[data-testid="bpmn-flash"]')).toBeNull();

    rerender(<BpmnDiagramView xml={flashXml} flashBlock={{ index: 2, nonce: 2 }} />);
    const flash = container.querySelector('[data-testid="bpmn-flash"]');
    expect(flash?.closest('[data-block-index]')?.getAttribute('data-block-index')).toBe('2');
    expect(flash?.getAttribute('class')).toContain('block-flash');
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
  });

  it('does not scroll when the flash comes from hover', () => {
    const flashXml = SAMPLE_XML.replace('id="t1"', 'id="b_2_maven"').replace('bpmnElement="t1"', 'bpmnElement="b_2_maven"');
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const { container } = render(<BpmnDiagramView xml={flashXml} flashBlock={{ index: 2, nonce: 1, scroll: false }} />);
    expect(container.querySelector('[data-testid="bpmn-flash"]')).not.toBeNull();
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('reports the block index when hovering over a box', () => {
    const hoverXml = SAMPLE_XML.replace('id="t1"', 'id="b_2_maven"').replace('bpmnElement="t1"', 'bpmnElement="b_2_maven"');
    const onHoverBlock = vi.fn();
    const { container } = render(<BpmnDiagramView xml={hoverXml} onHoverBlock={onHoverBlock} />);
    fireEvent.mouseEnter(container.querySelector('[data-block-index="2"]')!);
    expect(onHoverBlock).toHaveBeenCalledWith(2);
  });

  it('shows block number and line range when infoByBlock is set', () => {
    const infoXml = SAMPLE_XML.replace('id="t1"', 'id="b_2_maven"').replace('bpmnElement="t1"', 'bpmnElement="b_2_maven"');
    render(<BpmnDiagramView xml={infoXml} infoByBlock={new Map([[2, { start: 8, end: 12 }]])} />);
    // Block index is shown 1-based (#3), line range from the definition
    expect(screen.getByText('#3 · Z.8–12')).toBeInTheDocument();
  });

  it('colors a box by live run status', () => {
    const statusXml = SAMPLE_XML.replace('id="t1"', 'id="b_2_maven"').replace('bpmnElement="t1"', 'bpmnElement="b_2_maven"');
    const { container } = render(
      <BpmnDiagramView xml={statusXml} statusByBlock={new Map([[2, 'running']])} />,
    );
    const rect = container.querySelector('rect');
    expect(rect?.getAttribute('class')).toContain('stroke-accent');
    expect(rect?.getAttribute('class')).toContain('animate-pulse');
  });

  it('shows type marker and tooltip on the task', () => {
    const { container } = render(<BpmnDiagramView xml={SAMPLE_XML} />);
    // scriptTask: SVG <title> tooltip with type label
    const titles = Array.from(container.querySelectorAll('title')).map((t) => t.textContent);
    expect(titles).toContain('Shell-Kommando');
    // Script marker: three lines at the top left of the box
    expect(container.querySelectorAll('g.stroke-text-secondary line').length).toBeGreaterThanOrEqual(3);
  });

  describe('Zoom', () => {
    // SAMPLE_XML: viewBox 752 × 180; jsdom measures no width, so fitted means scale 1
    const svgOf = (container: HTMLElement) => container.querySelector('svg[role="img"]')!;

    it('zooms in to a fixed size via button and fits again', () => {
      const { container } = render(<BpmnDiagramView xml={SAMPLE_XML} />);
      expect(svgOf(container).getAttribute('width')).toBeNull();

      fireEvent.click(screen.getByRole('button', { name: 'Vergrößern' }));
      expect(svgOf(container).getAttribute('width')).toBe('940');
      expect(svgOf(container).getAttribute('height')).toBe('225');
      expect(screen.getByRole('button', { name: 'Einpassen' }).textContent).toBe('125 %');

      fireEvent.click(screen.getByRole('button', { name: 'Einpassen' }));
      expect(svgOf(container).getAttribute('width')).toBeNull();
    });

    it('zooms with Ctrl + mouse wheel, without Ctrl the wheel keeps scrolling', () => {
      const { container } = render(<BpmnDiagramView xml={SAMPLE_XML} />);
      const scroller = svgOf(container).parentElement!;

      fireEvent.wheel(scroller, { deltaY: -100 });
      expect(svgOf(container).getAttribute('width')).toBeNull();

      const zoomed = fireEvent.wheel(scroller, { deltaY: -100, ctrlKey: true });
      expect(zoomed).toBe(false); // preventDefault: no browser page zoom
      expect(svgOf(container).getAttribute('width')).toBe('940');

      fireEvent.wheel(scroller, { deltaY: 100, ctrlKey: true });
      expect(svgOf(container).getAttribute('width')).toBe('752');
    });

    it('limits the zoom and disables the button at the limit', () => {
      render(<BpmnDiagramView xml={SAMPLE_XML} />);
      const zoomOut = screen.getByRole('button', { name: 'Verkleinern' });
      for (let i = 0; i < 10; i++) fireEvent.click(zoomOut);
      expect(screen.getByRole('button', { name: 'Einpassen' }).textContent).toBe('25 %');
      expect(zoomOut).toBeDisabled();
    });

    it('fits again after a change of orientation', () => {
      const { container, rerender } = render(<BpmnDiagramView xml={SAMPLE_XML} />);
      fireEvent.click(screen.getByRole('button', { name: 'Vergrößern' }));
      rerender(<BpmnDiagramView xml={SAMPLE_XML} orientation="vertical" />);
      // vertically fitted = native size, no carried-over zoom
      expect(screen.getByRole('button', { name: 'Einpassen' })).toBeDisabled();
      rerender(<BpmnDiagramView xml={SAMPLE_XML} />);
      expect(svgOf(container).getAttribute('width')).toBeNull();
    });
  });
});
