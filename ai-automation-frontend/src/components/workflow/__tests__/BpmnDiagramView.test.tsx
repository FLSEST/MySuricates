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

/** Ausschnitt eines echten Exports: „report" verarbeitet {{ block.tests.output }}. */
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

describe('BpmnDiagramView Datenfluss', () => {
  it('zeichnet das Datenobjekt und zwei gestrichelte Assoziationen', () => {
    useI18nStore.setState({ locale: 'de' });
    const { container } = render(<BpmnDiagramView xml={DATA_FLOW_XML} />);
    // Datenobjekt: Rechteck mit umgeknickter Ecke
    expect(container.querySelectorAll('path[data-bpmn="data-object"]')).toHaveLength(1);
    expect(screen.getByText('Ausgabe: tests')).toBeInTheDocument();
    // Assoziationen gestrichelt, Kontrollfluss durchgezogen
    const dashed = Array.from(container.querySelectorAll('polyline')).filter((p) =>
      p.hasAttribute('stroke-dasharray'),
    );
    expect(dashed).toHaveLength(2);
    expect(container.querySelectorAll('polyline')).toHaveLength(3);
  });

  it('lokalisiert die Datenobjekt-Beschriftung (EN)', () => {
    useI18nStore.setState({ locale: 'en' });
    render(<BpmnDiagramView xml={DATA_FLOW_XML} />);
    expect(screen.getByText('Output: tests')).toBeInTheDocument();
  });

  it('macht das Datenobjekt nicht anklickbar', () => {
    const onSelectBlock = vi.fn();
    const { container } = render(<BpmnDiagramView xml={DATA_FLOW_XML} onSelectBlock={onSelectBlock} />);
    // nur die beiden Tasks tragen eine Block-ID (b_<i>_…), das Datenobjekt nicht
    expect(container.querySelectorAll('g[role="button"]')).toHaveLength(2);
  });

  it('routet die Assoziationen auch im vertikalen Layout an die beteiligten Kästen', () => {
    const { container } = render(<BpmnDiagramView xml={DATA_FLOW_XML} orientation="vertical" />);
    const dashed = Array.from(container.querySelectorAll('polyline')).filter((p) =>
      p.hasAttribute('stroke-dasharray'),
    );
    expect(dashed).toHaveLength(2);
    // Neu-Routing statt gespiegelter Alt-Punkte: keine Kante behält die Original-Koordinaten
    expect(dashed.some((p) => (p.getAttribute('points') || '').includes('336,240'))).toBe(false);
  });
});

describe('BpmnDiagramView', () => {
  it('rendert alle Shapes und Kanten aus dem BPMN-DI', () => {
    const { container } = render(<BpmnDiagramView xml={SAMPLE_XML} />);
    expect(container.querySelectorAll('svg')).toHaveLength(1);
    expect(container.querySelectorAll('circle')).toHaveLength(2); // Start + Ende
    expect(container.querySelectorAll('rect')).toHaveLength(1); // Task
    expect(container.querySelectorAll('polygon')).toHaveLength(1); // Gateway
    expect(container.querySelectorAll('polyline')).toHaveLength(3); // Kanten
    expect(screen.getByText('Maven Tests ausführen')).toBeInTheDocument();
    expect(screen.getByText('ja')).toBeInTheDocument(); // Kanten-Label
  });

  it('zeigt eine Meldung bei ungültigem XML', () => {
    render(<BpmnDiagramView xml="kein xml" />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('zeigt eine eigene Meldung für wohlgeformtes BPMN ohne Layout-Daten', () => {
    const noDi = `<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
      <process id="p"><startEvent id="s"/></process></definitions>`;
    render(<BpmnDiagramView xml={noDi} />);
    // andere Meldung als bei kaputtem XML
    expect(screen.getByRole('alert')).toHaveTextContent(/kein Diagramm|no diagram/i);
  });

  it('transponiert das Layout bei vertikaler Ausrichtung', () => {
    const { container, rerender } = render(<BpmnDiagramView xml={SAMPLE_XML} orientation="horizontal" />);
    const h = container.querySelector('svg')!.getAttribute('viewBox')!.split(' ').map(Number);
    rerender(<BpmnDiagramView xml={SAMPLE_XML} orientation="vertical" />);
    const v = container.querySelector('svg')!.getAttribute('viewBox')!.split(' ').map(Number);
    expect(h[2]!).toBeGreaterThan(h[3]!); // horizontal: breiter als hoch
    expect(v[3]!).toBeGreaterThan(v[2]!); // vertikal: höher als breit
    // Stauchung: die vertikale Höhe ist deutlich kleiner als die horizontale Breite
    // (ohne Kompaktierung wäre sie ~gleich, weil transponiert). 0.85 lässt Padding-Spielraum.
    expect(v[3]!).toBeLessThan(h[2]! * 0.85);
  });

  it('dockt Pfeile im vertikalen Layout an den Kasten-Mitten an', () => {
    const { container } = render(<BpmnDiagramView xml={SAMPLE_XML} orientation="vertical" />);
    const rect = container.querySelector('rect')!; // Task-Box
    const cx = Number(rect.getAttribute('x')) + Number(rect.getAttribute('width')) / 2;
    const polyXs = Array.from(container.querySelectorAll('polyline')).flatMap((p) =>
      (p.getAttribute('points') || '').trim().split(/\s+/).map((pt) => Number(pt.split(',')[0])),
    );
    // Neu-Routing: mindestens eine Kante dockt exakt an der Task-Mitte an (früher ~10px daneben)
    expect(polyXs.some((x) => Math.abs(x - cx) < 0.5)).toBe(true);
  });

  it('lokalisiert das deutsche Typ-Präfix namenloser Kästen (EN)', () => {
    useI18nStore.setState({ locale: 'en' });
    const nameless = SAMPLE_XML.replace('name="Maven Tests ausführen"', 'name="KI: git status"');
    render(<BpmnDiagramView xml={nameless} />);
    // Präfix "KI:" wird zu "AI:", der Befehl "git status" bleibt unverändert
    expect(screen.getByText('AI: git status')).toBeInTheDocument();
    expect(screen.queryByText('KI: git status')).toBeNull();
  });

  it('lässt Block-Namen unübersetzt (nur Präfixe werden lokalisiert)', () => {
    useI18nStore.setState({ locale: 'en' });
    render(<BpmnDiagramView xml={SAMPLE_XML} />);
    // "Maven Tests ausführen" ist ein Nutzer-Name ohne Typ-Präfix → unverändert in EN
    expect(screen.getByText('Maven Tests ausführen')).toBeInTheDocument();
  });

  it('meldet den Block-Index beim Klick auf einen Kasten', () => {
    // scriptTask-ID trägt den Block-Index (b_2_…) wie im echten Export
    const clickableXml = SAMPLE_XML.replace('id="t1"', 'id="b_2_maven"').replace('bpmnElement="t1"', 'bpmnElement="b_2_maven"');
    const onSelectBlock = vi.fn();
    const { container } = render(<BpmnDiagramView xml={clickableXml} onSelectBlock={onSelectBlock} />);
    const clickable = container.querySelector('g[role="button"]');
    expect(clickable).not.toBeNull();
    fireEvent.click(clickable!);
    expect(onSelectBlock).toHaveBeenCalledWith({ blockIndex: 2 });
  });

  it('meldet Block- und Case-Index bei einem verschachtelten Switch-Case-Kasten', () => {
    // Switch-Case-Kind trägt den Pfad in der ID: b_<block>_c<case>_…
    const caseXml = SAMPLE_XML.replace('id="t1"', 'id="b_3_c1_0_alarm"').replace('bpmnElement="t1"', 'bpmnElement="b_3_c1_0_alarm"');
    const onSelectBlock = vi.fn();
    const { container } = render(<BpmnDiagramView xml={caseXml} onSelectBlock={onSelectBlock} />);
    fireEvent.click(container.querySelector('g[role="button"]')!);
    expect(onSelectBlock).toHaveBeenCalledWith({ blockIndex: 3, sub: { kind: 'case', caseIndex: 1 } });
  });

  it('kürzt zu lange Labels mit Ellipsis und hält sie in der Box', () => {
    const longXml = SAMPLE_XML.replace(
      'name="Maven Tests ausführen"',
      'name="Vollautomatische Integrationstestsuite mit ausführlicher Fehleranalyse und Reporting"',
    );
    const { container } = render(<BpmnDiagramView xml={longXml} />);
    const tspans = Array.from(container.querySelectorAll('tspan'));
    // max. 3 Zeilen, letzte endet mit Ellipsis
    expect(tspans.length).toBeLessThanOrEqual(3);
    expect(tspans.some((t) => t.textContent?.endsWith('…'))).toBe(true);
    // voller Name bleibt als Tooltip erhalten
    const titles = Array.from(container.querySelectorAll('title')).map((t) => t.textContent);
    expect(titles).toContain('Vollautomatische Integrationstestsuite mit ausführlicher Fehleranalyse und Reporting');
  });

  it('zeigt Block-Nummer und Zeilenbereich, wenn infoByBlock gesetzt ist', () => {
    const infoXml = SAMPLE_XML.replace('id="t1"', 'id="b_2_maven"').replace('bpmnElement="t1"', 'bpmnElement="b_2_maven"');
    render(<BpmnDiagramView xml={infoXml} infoByBlock={new Map([[2, { start: 8, end: 12 }]])} />);
    // Block-Index wird 1-basiert angezeigt (#3), Zeilenbereich aus der Definition
    expect(screen.getByText('#3 · Z.8–12')).toBeInTheDocument();
  });

  it('färbt einen Kasten nach Live-Run-Status', () => {
    const statusXml = SAMPLE_XML.replace('id="t1"', 'id="b_2_maven"').replace('bpmnElement="t1"', 'bpmnElement="b_2_maven"');
    const { container } = render(
      <BpmnDiagramView xml={statusXml} statusByBlock={new Map([[2, 'running']])} />,
    );
    const rect = container.querySelector('rect');
    expect(rect?.getAttribute('class')).toContain('stroke-accent');
    expect(rect?.getAttribute('class')).toContain('animate-pulse');
  });

  it('zeigt Typ-Marker und Tooltip am Task', () => {
    const { container } = render(<BpmnDiagramView xml={SAMPLE_XML} />);
    // scriptTask: SVG-<title>-Tooltip mit Typ-Label
    const titles = Array.from(container.querySelectorAll('title')).map((t) => t.textContent);
    expect(titles).toContain('Shell-Kommando');
    // Skript-Marker: drei Linien oben links im Kasten
    expect(container.querySelectorAll('g.stroke-text-secondary line').length).toBeGreaterThanOrEqual(3);
  });
});
