import { describe, it, expect } from 'vitest';
import { diagramBounds, localizeName, parseBpmn, selectionOf, verticalLayout, wrapLabel } from '../bpmnLayout';

const HEAD = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
             xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
             id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">`;

/** Start → script → end, horizontal as exported by the backend. */
const MINIMAL_XML = `${HEAD}
  <process id="p1" isExecutable="false">
    <startEvent id="start" name="Start" />
    <scriptTask id="b_0_build" name="Maven Tests ausführen" />
    <endEvent id="end" name="Ende" />
    <sequenceFlow id="f1" sourceRef="start" targetRef="b_0_build" />
    <sequenceFlow id="f2" sourceRef="b_0_build" targetRef="end" name="ok" />
  </process>
  <bpmndi:BPMNDiagram id="d1">
    <bpmndi:BPMNPlane id="pl1" bpmnElement="p1">
      <bpmndi:BPMNShape id="sh1" bpmnElement="start"><dc:Bounds x="100" y="182" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh2" bpmnElement="b_0_build"><dc:Bounds x="286" y="160" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh3" bpmnElement="end"><dc:Bounds x="536" y="182" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed1" bpmnElement="f1"><di:waypoint x="136" y="200"/><di:waypoint x="286" y="200"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="ed2" bpmnElement="f2"><di:waypoint x="386" y="200"/><di:waypoint x="536" y="200"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`;

/** A block with an output data object (dataOutputAssociation as a child element). */
const DATA_XML = `${HEAD}
  <process id="p1" isExecutable="false">
    <scriptTask id="b_0_tests" name="tests">
      <dataOutputAssociation id="out0"><targetRef>dataref_tests</targetRef></dataOutputAssociation>
    </scriptTask>
    <dataObject id="data_tests"/>
    <dataObjectReference id="dataref_tests" dataObjectRef="data_tests" name="Ausgabe: tests"/>
  </process>
  <bpmndi:BPMNDiagram id="d1">
    <bpmndi:BPMNPlane id="pl1" bpmnElement="p1">
      <bpmndi:BPMNShape id="sh1" bpmnElement="b_0_tests"><dc:Bounds x="286" y="160" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sh2" bpmnElement="dataref_tests"><dc:Bounds x="318" y="300" width="36" height="50"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="ed1" bpmnElement="out0"><di:waypoint x="336" y="240"/><di:waypoint x="336" y="300"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`;

describe('parseBpmn', () => {
  it('reads boxes with type, name and bounds as well as edges with ends and waypoints', () => {
    const d = parseBpmn(MINIMAL_XML)!;
    expect(d.shapes.map((s) => [s.id, s.kind, s.name])).toEqual([
      ['start', 'startEvent', 'Start'],
      ['b_0_build', 'scriptTask', 'Maven Tests ausführen'],
      ['end', 'endEvent', 'Ende'],
    ]);
    expect(d.shapes[1]).toMatchObject({ x: 286, y: 160, w: 100, h: 80, loop: null });
    expect(d.edges[1]).toEqual({
      id: 'ed2', name: 'ok', sourceRef: 'b_0_build', targetRef: 'end', dashed: false,
      points: [[386, 200], [536, 200]],
    });
  });

  it('resolves data associations to the surrounding activity and draws them dashed', () => {
    const edge = parseBpmn(DATA_XML)!.edges[0]!;
    expect(edge).toMatchObject({ sourceRef: 'b_0_tests', targetRef: 'dataref_tests', dashed: true });
  });

  it('returns null for broken XML', () => {
    expect(parseBpmn('<kein xml')).toBeNull();
  });
});

describe('verticalLayout', () => {
  it('transposes about the center, compresses gaps to 70px and re-routes edges bottom→top', () => {
    const v = verticalLayout(parseBpmn(MINIMAL_XML)!);
    expect(v.shapes.map((s) => [s.x, s.y, s.w, s.h])).toEqual([
      [182, 100, 36, 36],
      [150, 206, 100, 80],
      [182, 356, 36, 36],
    ]);
    expect(v.edges.map((e) => e.points)).toEqual([
      [[200, 136], [200, 206]],
      [[200, 286], [200, 356]],
    ]);
  });

  it('places a data object below its producer', () => {
    const v = verticalLayout(parseBpmn(DATA_XML)!);
    const producer = v.shapes.find((s) => s.id === 'b_0_tests')!;
    const data = v.shapes.find((s) => s.id === 'dataref_tests')!;
    expect(data.y).toBe(producer.y + producer.h + 10);
  });
});

describe('diagramBounds', () => {
  it('encloses boxes, label space and edges with a 40px margin', () => {
    expect(diagramBounds(parseBpmn(MINIMAL_XML)!)).toEqual({ minX: 60, minY: 120, width: 552, height: 180 });
  });
});

describe('wrapLabel', () => {
  it('wraps at word boundaries', () => {
    expect(wrapLabel('Maven Tests ausführen', 100)).toEqual(['Maven Tests', 'ausführen']);
  });

  it('splits overlong words hard and truncates to three lines with an ellipsis', () => {
    const lines = wrapLabel('x'.repeat(60), 100);
    expect(lines).toHaveLength(3);
    expect(lines[0]).toBe('x'.repeat(13));
    expect(lines[2]!.endsWith('…')).toBe(true);
  });
});

describe('localizeName', () => {
  const maps = { prefix: { KI: 'AI', Freigabe: 'Approval' }, tip: {}, event: { Ende: 'End' } };

  it('translates event names, type prefixes and unnamed types, leaves block names alone', () => {
    expect(localizeName('Ende', maps)).toBe('End');
    expect(localizeName('KI: git status', maps)).toBe('AI: git status');
    expect(localizeName('Freigabe', maps)).toBe('Approval');
    expect(localizeName('build', maps)).toBe('build');
  });
});

describe('selectionOf', () => {
  it('derives block and sub-path from the element ID', () => {
    expect(selectionOf('start')).toBeNull();
    expect(selectionOf('b_4_report')).toEqual({ blockIndex: 4 });
    expect(selectionOf('b_2_c1_task')).toEqual({ blockIndex: 2, sub: { kind: 'case', caseIndex: 1 } });
    expect(selectionOf('b_2_default')).toEqual({ blockIndex: 2, sub: { kind: 'default' } });
    expect(selectionOf('b_3_then')).toEqual({ blockIndex: 3, sub: { kind: 'then' } });
    expect(selectionOf('b_3_else')).toEqual({ blockIndex: 3, sub: { kind: 'else' } });
    expect(selectionOf('b_0_body')).toEqual({ blockIndex: 0, sub: { kind: 'body' } });
  });
});
