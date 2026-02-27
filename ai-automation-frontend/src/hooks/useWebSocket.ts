import { useEffect, useRef, useState, useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { AgenticState, AgenticIteration, AgenticPhase, PendingHumanInput } from '../types';

interface StepMessage {
  stepIndex: number;
  status: string;
  input: string;
  output: string;
  durationMs: number;
}

interface StatusMessage {
  type: 'status';
  runId: string;
  status: string;
  stepsCompleted?: number;
}

interface ApprovalRequiredMessage {
  type: 'approval_required';
  blockId: string;
  question: string;
  options: string[];
  stepIndex: number;
}

interface ApprovalResolvedMessage {
  type: 'approval_resolved';
  stepIndex: number;
  answer: string;
}

interface HumanInputRequiredMessage {
  type: 'human_input_required';
  blockId: string;
  prompt: string;
  inputType: 'text' | 'number' | 'textarea' | 'select';
  options: string[];
  defaultValue: string;
  required: boolean;
  validationPattern: string | null;
  variableName: string;
  stepIndex: number;
}

interface HumanInputResolvedMessage {
  type: 'human_input_resolved';
  stepIndex: number;
  answer: string;
}

// Agentic message types
interface AgenticStartedMessage {
  type: 'agentic_started';
  stepIndex: number;
  goal: string;
  maxIterations: number;
}

interface AgenticReasoningMessage {
  type: 'agentic_reasoning';
  stepIndex: number;
  iteration: number;
}

interface AgenticReasonedMessage {
  type: 'agentic_reasoned';
  stepIndex: number;
  iteration: number;
  reasoning: string;
}

interface AgenticActingMessage {
  type: 'agentic_acting';
  stepIndex: number;
  iteration: number;
  action: string;
}

interface AgenticActedMessage {
  type: 'agentic_acted';
  stepIndex: number;
  iteration: number;
  action: string;
  output: string;
  exitCode: number;
}

interface AgenticGoalAchievedMessage {
  type: 'agentic_goal_achieved' | 'agentic_goal_impossible';
  stepIndex: number;
  iteration: number;
  summary: string;
}

interface AgenticCompletedMessage {
  type: 'agentic_completed';
  stepIndex: number;
  goalAchieved: boolean;
  totalIterations: number;
  durationMs: number;
}

interface AgenticErrorMessage {
  type: 'agentic_error';
  stepIndex: number;
  iteration: number;
  phase: string;
  error: string;
}

interface AgenticApprovalMessage {
  type: 'agentic_approval_required';
  stepIndex: number;
  iteration: number;
  action: string;
  rationale: string;
}

type AgenticMessage =
  | AgenticStartedMessage
  | AgenticReasoningMessage
  | AgenticReasonedMessage
  | AgenticActingMessage
  | AgenticActedMessage
  | AgenticGoalAchievedMessage
  | AgenticCompletedMessage
  | AgenticErrorMessage
  | AgenticApprovalMessage;

type WsMessage = StepMessage | StatusMessage | ApprovalRequiredMessage | ApprovalResolvedMessage | HumanInputRequiredMessage | HumanInputResolvedMessage | AgenticMessage;

export interface PendingApproval {
  blockId: string;
  question: string;
  options: string[];
  stepIndex: number;
}

function isStatusMessage(data: WsMessage): data is StatusMessage {
  return 'type' in data && data.type === 'status';
}

function isApprovalRequired(data: WsMessage): data is ApprovalRequiredMessage {
  return 'type' in data && data.type === 'approval_required';
}

function isApprovalResolved(data: WsMessage): data is ApprovalResolvedMessage {
  return 'type' in data && data.type === 'approval_resolved';
}

function isHumanInputRequired(data: WsMessage): data is HumanInputRequiredMessage {
  return 'type' in data && data.type === 'human_input_required';
}

function isHumanInputResolved(data: WsMessage): data is HumanInputResolvedMessage {
  return 'type' in data && data.type === 'human_input_resolved';
}

function isAgenticMessage(data: WsMessage): data is AgenticMessage {
  return 'type' in data && typeof data.type === 'string' && data.type.startsWith('agentic_');
}

function handleAgenticMessage(
  msg: AgenticMessage,
  setAgenticStates: React.Dispatch<React.SetStateAction<Map<number, AgenticState>>>,
) {
  const stepIndex = msg.stepIndex;

  setAgenticStates((prev) => {
    const updated = new Map(prev);
    const current = updated.get(stepIndex) || {
      stepIndex,
      goal: '',
      maxIterations: 0,
      currentIteration: 0,
      iterations: [],
      goalAchieved: null,
    };

    switch (msg.type) {
      case 'agentic_started':
        updated.set(stepIndex, {
          ...current,
          goal: msg.goal,
          maxIterations: msg.maxIterations,
        });
        break;

      case 'agentic_reasoning': {
        const iter: AgenticIteration = {
          iteration: msg.iteration,
          phase: 'reasoning',
          timestamp: new Date().toISOString(),
        };
        updated.set(stepIndex, {
          ...current,
          currentIteration: msg.iteration,
          iterations: [...current.iterations.filter((i) => i.iteration !== msg.iteration), iter],
        });
        break;
      }

      case 'agentic_reasoned': {
        const iters = current.iterations.map((i) =>
          i.iteration === msg.iteration ? { ...i, reasoning: msg.reasoning, phase: 'acting' as AgenticPhase } : i,
        );
        updated.set(stepIndex, { ...current, iterations: iters });
        break;
      }

      case 'agentic_acting': {
        const iters = current.iterations.map((i) =>
          i.iteration === msg.iteration ? { ...i, action: msg.action, phase: 'acting' as AgenticPhase } : i,
        );
        updated.set(stepIndex, { ...current, iterations: iters });
        break;
      }

      case 'agentic_acted': {
        const iters = current.iterations.map((i) =>
          i.iteration === msg.iteration
            ? { ...i, output: msg.output, exitCode: msg.exitCode, action: msg.action, phase: 'acted' as AgenticPhase }
            : i,
        );
        updated.set(stepIndex, { ...current, iterations: iters });
        break;
      }

      case 'agentic_goal_achieved':
      case 'agentic_goal_impossible':
        updated.set(stepIndex, {
          ...current,
          goalAchieved: msg.type === 'agentic_goal_achieved',
          finalSummary: msg.summary,
        });
        break;

      case 'agentic_completed':
        updated.set(stepIndex, {
          ...current,
          goalAchieved: msg.goalAchieved,
          currentIteration: msg.totalIterations,
        });
        break;

      case 'agentic_error': {
        const iter: AgenticIteration = {
          iteration: msg.iteration,
          phase: 'error',
          output: msg.error,
          timestamp: new Date().toISOString(),
        };
        const existingIters = current.iterations.filter((i) => i.iteration !== msg.iteration);
        updated.set(stepIndex, { ...current, iterations: [...existingIters, iter] });
        break;
      }

      case 'agentic_approval_required': {
        const iters = current.iterations.map((i) =>
          i.iteration === msg.iteration ? { ...i, phase: 'approval' as AgenticPhase, action: msg.action } : i,
        );
        updated.set(stepIndex, { ...current, iterations: iters });
        break;
      }
    }

    return updated;
  });
}

const MAX_LOG_ENTRIES = 500;

export function useExecutionWebSocket(runId: string | null, workflowId?: string) {
  const [logs, setLogs] = useState<Array<{ timestamp: string; level: string; message: string }>>([]);
  const [connected, setConnected] = useState(false);
  const [pendingApproval, setPendingApproval] = useState<PendingApproval | null>(null);
  const [pendingHumanInput, setPendingHumanInput] = useState<PendingHumanInput | null>(null);
  const [agenticStates, setAgenticStates] = useState<Map<number, AgenticState>>(new Map());
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

    const client = new Client({
      webSocketFactory: () => new SockJS(`${window.location.origin}/ws`),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        client.subscribe(`/topic/executions/${runId}/logs`, (message) => {
          try {
            const data = JSON.parse(message.body) as WsMessage;

            if (isAgenticMessage(data)) {
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
  }, [runId]);

  const clearLogs = useCallback(() => setLogs([]), []);

  return { logs, connected, clearLogs, pendingApproval, pendingHumanInput, agenticStates };
}
