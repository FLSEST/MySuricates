import { useEffect, useRef, useState, useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { api } from '../api/client';
import { useSettingsStore } from '../stores/settingsStore';
import type { AgenticState, PendingHumanInput } from '../types';
import type { WsMessage, ForEachState, PendingApproval, LiveLine, LiveLogSnapshot } from '../types/websocket';
import {
  isStatusMessage, isApprovalRequired, isApprovalResolved,
  isHumanInputRequired, isHumanInputResolved, isAgenticMessage, isForEachMessage,
  isLiveOutputMessage, isStepMessage,
  handleForEachMessage, handleAgenticMessage,
} from '../types/websocket';
import {
  appendCapped, createStepLogContext, eventToEntry, liveLinesToEntries, skippedMarker,
  type ExecutionLogEntry,
} from './executionLogEntries';

export type { ForEachItemState, ForEachState, PendingApproval } from '../types/websocket';
export type { ExecutionLogEntry } from './executionLogEntries';

const TERMINAL_STATUSES = ['COMPLETED', 'FAILED', 'CANCELLED'];

export interface ExecutionWebSocketOptions {
  /** Stream block output and replay buffered lines on connect (default true). */
  liveOutput?: boolean;
}

export function useExecutionWebSocket(runId: string | null, workflowId?: string, options: ExecutionWebSocketOptions = {}) {
  const liveOutput = options.liveOutput ?? true;
  const [logs, setLogs] = useState<ExecutionLogEntry[]>([]);
  const [connected, setConnected] = useState(false);
  const [pendingApproval, setPendingApproval] = useState<PendingApproval | null>(null);
  const [pendingHumanInput, setPendingHumanInput] = useState<PendingHumanInput | null>(null);
  const [agenticStates, setAgenticStates] = useState<Map<number, AgenticState>>(new Map());
  const [forEachStates, setForEachStates] = useState<Map<number, ForEachState>>(new Map());
  const clientRef = useRef<Client | null>(null);
  const queryClient = useQueryClient();

  const workflowIdRef = useRef(workflowId);
  useEffect(() => { workflowIdRef.current = workflowId; }, [workflowId]);

  const queryClientRef = useRef(queryClient);
  useEffect(() => { queryClientRef.current = queryClient; }, [queryClient]);

  const appendEntries = useCallback((entries: ExecutionLogEntry[]) => {
    if (entries.length > 0) setLogs((prev) => appendCapped(prev, entries));
  }, []);

  useEffect(() => {
    if (!runId) return;

    let disposed = false;
    const stepContext = createStepLogContext();
    const live = { bufferId: null as string | null, lastSeq: -1 };
    let eventCounter = 0;
    const nextId = () => `evt-${eventCounter++}`;

    /** Appends lines newer than the last seen one; a gap becomes a "lines skipped" marker. */
    const applyLive = (bufferId: string, lines: LiveLine[]) => {
      if (live.bufferId !== bufferId) {
        live.bufferId = bufferId;
        live.lastSeq = -1;
      }
      const fresh = lines.filter((line) => line.seq > live.lastSeq);
      const first = fresh[0];
      const last = fresh[fresh.length - 1];
      if (!first || !last) return;
      const entries: ExecutionLogEntry[] = [];
      const missing = first.seq - (live.lastSeq + 1);
      if (missing > 0) entries.push(skippedMarker(bufferId, first.seq, missing));
      entries.push(...liveLinesToEntries(bufferId, fresh, stepContext));
      live.lastSeq = last.seq;
      appendEntries(entries);
    };

    const apiKey = useSettingsStore.getState().apiKey;
    const wsUrl = `${window.location.origin}/ws${apiKey ? `?apiKey=${apiKey}` : ''}`;

    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        // Live batches arriving while the replay request is pending wait for it, so lines stay ordered.
        let replaying = liveOutput;
        const queued: { bufferId: string; lines: LiveLine[] }[] = [];

        client.subscribe(`/topic/executions/${runId}/logs`, (message) => {
          try {
            const data = JSON.parse(message.body) as WsMessage;

            if (isLiveOutputMessage(data)) {
              if (!liveOutput) return;
              if (replaying) queued.push(data);
              else applyLive(data.bufferId, data.lines);
              return;
            }

            if (isForEachMessage(data)) {
              handleForEachMessage(data, setForEachStates);
            } else if (isAgenticMessage(data)) {
              handleAgenticMessage(data, setAgenticStates);
            } else if (isApprovalRequired(data)) {
              setPendingApproval({
                blockId: data.blockId,
                question: data.question,
                options: data.options,
                stepIndex: data.stepIndex,
              });
            } else if (isApprovalResolved(data)) {
              setPendingApproval(null);
            } else if (isHumanInputRequired(data)) {
              setPendingHumanInput({
                blockId: data.blockId,
                prompt: data.prompt,
                inputType: data.inputType,
                options: data.options,
                defaultValue: data.defaultValue,
                required: data.required,
                validationPattern: data.validationPattern,
                variableName: data.variableName,
                stepIndex: data.stepIndex,
              });
            } else if (isHumanInputResolved(data)) {
              setPendingHumanInput(null);
            } else if (isStatusMessage(data) && TERMINAL_STATUSES.includes(data.status)) {
              // Clear pending dialogs on terminal status
              setPendingApproval(null);
              setPendingHumanInput(null);
            }
            appendEntries([eventToEntry(data, nextId(), stepContext)]);

            // Invalidate run queries on step updates and terminal status changes
            const shouldRefetch =
              (isStatusMessage(data) && TERMINAL_STATUSES.includes(data.status)) ||
              isStepMessage(data) || isForEachMessage(data);
            if (shouldRefetch && workflowIdRef.current) {
              queryClientRef.current.invalidateQueries({ queryKey: ['workflows', workflowIdRef.current, 'runs'] });
            }
          } catch {
            appendEntries([{ id: nextId(), timestamp: new Date().toISOString(), level: 'INFO', message: message.body }]);
          }
        });

        if (liveOutput) {
          api.get<LiveLogSnapshot>(`/executions/${runId}/live-log?afterSeq=-1`)
            .then((snapshot) => {
              if (disposed || !snapshot?.available || !snapshot.bufferId) return;
              // Steps whose start message was missed (reload, late open) still get their block name.
              for (const [index, name] of Object.entries(snapshot.stepNames ?? {})) {
                if (!stepContext.labels.has(Number(index))) stepContext.labels.set(Number(index), name);
              }
              applyLive(snapshot.bufferId, snapshot.lines);
            })
            .catch(() => { /* replay is best effort — live batches still arrive */ })
            .finally(() => {
              replaying = false;
              if (!disposed) queued.splice(0).forEach((batch) => applyLive(batch.bufferId, batch.lines));
            });
        }
      },
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
    });

    clientRef.current = client;
    client.activate();

    return () => {
      disposed = true;
      client.deactivate();
      clientRef.current = null;
      // A different run must not inherit this run's lines.
      setLogs([]);
    };
  }, [runId, liveOutput, appendEntries]);

  const clearLogs = useCallback(() => setLogs([]), []);

  return { logs, connected, clearLogs, pendingApproval, pendingHumanInput, agenticStates, forEachStates };
}
