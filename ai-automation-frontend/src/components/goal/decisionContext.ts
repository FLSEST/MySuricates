import { createContext, useContext } from 'react';

/**
 * Where decisions may be made on the goal page (68a5e24e): the goal and whether the backend knows
 * decisions. Without a provider, the markers remain pure display.
 */
export interface DecisionScope {
  goalId: string;
  enabled: boolean;
}

export const DecisionContext = createContext<DecisionScope | null>(null);

export const useDecisionScope = () => useContext(DecisionContext);
