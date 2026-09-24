/**
 * True nur fuer app-interne Pfade, die gefahrlos an react-routers navigate() gehen duerfen.
 *
 * Ein Pfad, der mit "//" oder "/\\" beginnt, ist protokollrelativ bzw. wird von Browsern und
 * aelteren react-router-Versionen als externes Ziel aufgeloest — "/\\evil.example" landet also
 * ausserhalb der App (GHSA open redirect, react-router <= 7.17.0). Assistenten-Antworten und
 * gerendertes Markdown sind LLM-Text und koennen untrusted Inhalte aus Workflow-Ausgaben oder
 * Webhooks enthalten, deshalb wird hier geprueft statt vertraut.
 */
export function isInternalAppPath(href: string | undefined): href is string {
  if (!href || !href.startsWith('/')) return false;
  const second = href[1];
  return second !== '/' && second !== '\\';
}
