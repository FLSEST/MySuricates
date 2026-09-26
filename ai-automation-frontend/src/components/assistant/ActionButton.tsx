import { useNavigate } from 'react-router-dom';
import { Play, Eye, Sparkles, MessageCircle } from 'lucide-react';
import type { AssistantAction } from '../../hooks/useAssistant';

/** One action of an answer as a button; ask_choice renders one button per option. */
export function ActionButton({ action, onClose, onSend, onExecute }: {
  action: AssistantAction;
  onClose: () => void;
  onSend?: (text: string) => void;
  onExecute?: (workflowId: string) => void;
}) {
  const navigate = useNavigate();

  // ask_choice: render each option as its own button
  if (action.type === 'ask_choice' && action.options) {
    return (
      <>
        {action.options.map((opt) => (
          <button
            key={opt}
            onClick={() => onSend?.(opt)}
            className="flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium
                       bg-accent/15 text-accent hover:bg-accent/25 transition-colors border border-accent/20"
          >
            <MessageCircle className="w-3 h-3" />
            {opt}
          </button>
        ))}
      </>
    );
  }

  const icon = action.type === 'run_workflow' ? <Play className="w-3 h-3" />
    : action.type === 'show_workflow' || action.type === 'show_run' ? <Eye className="w-3 h-3" />
    : <Sparkles className="w-3 h-3" />;

  const handleClick = () => {
    switch (action.type) {
      case 'run_workflow':
        if (action.id) onExecute?.(action.id);
        break;
      case 'show_workflow':
        navigate(`/workflows/${action.id}`);
        onClose();
        break;
      case 'show_run':
        if (action.id) navigate(`/runs/${action.id}`);
        else navigate('/runs');
        onClose();
        break;
      case 'start_wish':
        navigate('/wishes', { state: { prefill: action.name } });
        onClose();
        break;
    }
  };

  return (
    <button
      onClick={handleClick}
      className="flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium
                 bg-accent/15 text-accent hover:bg-accent/25 transition-colors border border-accent/20"
    >
      {icon}
      {action.label}
    </button>
  );
}
