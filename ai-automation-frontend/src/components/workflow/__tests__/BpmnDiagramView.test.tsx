import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import BpmnDiagramView from '../BpmnDiagramView';

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

  it('zeigt Typ-Marker und Tooltip am Task', () => {
    const { container } = render(<BpmnDiagramView xml={SAMPLE_XML} />);
    // scriptTask: SVG-<title>-Tooltip mit Typ-Label
    const titles = Array.from(container.querySelectorAll('title')).map((t) => t.textContent);
    expect(titles).toContain('Shell-Kommando');
    // Skript-Marker: drei Linien oben links im Kasten
    expect(container.querySelectorAll('g.stroke-text-secondary line').length).toBeGreaterThanOrEqual(3);
  });
});
