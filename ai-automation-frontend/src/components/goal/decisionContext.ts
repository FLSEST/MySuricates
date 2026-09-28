import { createContext, useContext } from 'react';

/**
 * Wo auf der Zielseite entschieden werden darf (68a5e24e): das Ziel und ob das Backend Entscheidungen
 * kennt. Ohne Anbieter bleiben die Markierungen reine Anzeige.
 */
export interface DecisionScope {
  goalId: string;
  enabled: boolean;
}

export const DecisionContext = createContext<DecisionScope | null>(null);

export const useDecisionScope = () => useContext(DecisionContext);
