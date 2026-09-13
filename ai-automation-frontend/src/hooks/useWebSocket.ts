import { useEffect, useRef, useState, useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useSettingsStore } from '../stores/settingsStore';
import type { AgenticState, PendingHumanInput } from '../types';
import type { WsMessage, ForEachState, PendingApproval } from '../types/websocket';
import {
  isStatusMessage, isApprovalRequired, isApprovalResolved,
  isHumanInputRequired, isHumanInputResolved, isAgenticMessage, isForEachMessage,
  handleForEachMessage, handleAgenticMessage,
} from '../types/websocket';

export type { ForEachItemState, ForEachState, PendingApproval } from '../types/websocket';

const MAX_LOG_ENTRIES = 500;

export function useExecutionWebSocket(runId: string | null, workflowId?: string) {
  const [logs, setLogs] = useState<Array<{ timestamp: string; level: string; message: string }>>([]);
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

  const appendLog = useCallback((entry: { timestamp: string; level: string; message: string }) => {
    setLogs((prev) => {
      const next = [...prev, entry];
      return next.length > MAX_LOG_ENTRIES ? next.slice(-MAX_LOG_ENTRIES) : next;
    });
  }, []);

  useEffect(() => {
    if (!runId) return;

    const apiKey = useSettingsStore.getState().apiKey;
    const wsUrl = `${window.location.origin}/ws${apiKey ? `?apiKey=${apiKey}` : ''}`;

    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe(`/topic/executions/${runId}/logs`, (message) => {
          try {
            const data = JSON.parse(message.body) as WsMessage;

            if (isForEachMessage(data)) {
              handleForEachMessage(data, setForEachStates);
              const pct = data.type === 'foreach_item_completed'
                ? `${Math.round(((data.itemIndex + 1) / data.totalItems) * 100)}%`
                : '';
              const status = data.type === 'foreach_item_completed' ? ` [${data.status}]` : '';
              appendLog({
                timestamp: new Date().toISOString(),
                level: data.type === 'foreach_item_completed' && data.status === 'FAILED' ? 'ERROR' : 'INFO',
                message: data.type === 'foreach_item_started'
                  ? `[forEach] Starting item ${data.itemIndex + 1}/${data.totalItems}: ${data.item}`
                  : `[forEach] ${data.itemIndex + 1}/${data.totalItems} (${pct})${status}: ${data.item}`,
              });
            } else if (isAgenticMessage(data)) {
              handleAgenticMessage(data, setAgenticStates);

              // Also add to logs for log viewer
              const logLevel = data.type === 'agentic_error' ? 'ERROR' : 'INFO';
              const logMsg =
                data.type === 'agentic_started' ? `[Agentic] Goal: ${data.goal}`
                : data.type === 'agentic_reasoned' ? `[Agentic #${data.iteration}] Reasoning complete`
                : data.type === 'agentic_acted' ? `[Agentic #${data.iteration}] Action executed (exit: ${data.exitCode})`
                : data.type === 'agentic_goal_achieved' ? `[Agentic] Goal achieved: ${data.summary}`
                : data.type === 'agentic_goal_impossible' ? `[Agentic] Goal impossible: ${data.summary}`
                : data.type === 'agentic_completed' ? `[Agentic] Completed (${data.totalIterations} iterations, goal: ${data.goalAchieved})`
                : data.type === 'agentic_error' ? `[Agentic #${data.iteration}] Error: ${data.error}`
                : `[Agentic] ${data.type}`;

              appendLog({ timestamp: new Date().toISOString(), level: logLevel, message: logMsg });
            } else if (isApprovalRequired(data)) {
              setPendingApproval({
                blockId: data.blockId,
                question: data.question,
                options: data.options,
                stepIndex: data.stepIndex,
              });
              appendLog({
                timestamp: new Date().toISOString(),
                level: 'WARN',
                message: `[Step ${data.stepIndex}] Approval required: ${data.question}`,
              });
            } else if (isApprovalResolved(data)) {
              setPendingApproval(null);
              appendLog({
                timestamp: new Date().toISOString(),
                level: 'INFO',
                message: `[Step ${data.stepIndex}] Approval resolved: ${data.answer}`,
              });
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
              appendLog({
                timestamp: new Date().toISOString(),
                level: 'WARN',
                message: `[Step ${data.stepIndex}] Human input required: ${data.prompt}`,
              });
            } else if (isHumanInputResolved(data)) {
              setPendingHumanInput(null);
              appendLog({
                timestamp: new Date().toISOString(),
                level: 'INFO',
                message: `[Step ${data.stepIndex}] Human input received: ${data.answer}`,
              });
            } else if (isStatusMessage(data)) {
              // Clear pending dialogs on terminal status
              if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(data.status)) {
                setPendingApproval(null);
                setPendingHumanInput(null);
              }
              appendLog({
                timestamp: new Date().toISOString(),
                level: 'INFO',
                message: `Run status: ${data.status}${data.stepsCompleted != null ? ` (${data.stepsCompleted} steps completed)` : ''}`,
              });
            } else {
              appendLog({
                timestamp: new Date().toISOString(),
                level: data.status === 'FAILED' ? 'ERROR' : 'INFO',
                message: `[Step ${data.stepIndex}] ${data.output || data.input}`,
              });
            }

            // Invalidate run queries on step updates and terminal status changes
            const shouldRefetch =
              (isStatusMessage(data) && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(data.status)) ||
              (!isStatusMessage(data) && !isAgenticMessage(data) && !isApprovalRequired(data) && !isApprovalResolved(data) && !isHumanInputRequired(data) && !isHumanInputResolved(data) && 'stepIndex' in data);
            if (shouldRefetch && workflowIdRef.current) {
              queryClientRef.current.invalidateQueries({ queryKey: ['workflows', workflowIdRef.current, 'runs'] });
            }
          } catch {
            appendLog({ timestamp: new Date().toISOString(), level: 'INFO', message: message.body });
          }
        });
      },
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
    });

    clientRef.current = client;
    client.activate();

    return () => {
      client.deactivate();
      clientRef.current = null;
    };
  }, [runId, appendLog]);

  const clearLogs = useCallback(() => setLogs([]), []);

  return { logs, connected, clearLogs, pendingApproval, pendingHumanInput, agenticStates, forEachStates };
}
