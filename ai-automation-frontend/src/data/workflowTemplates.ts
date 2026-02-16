import type { BlockDefinition } from '../types';

export interface WorkflowTemplate {
  id: string;
  icon: string;
  nameKey: string;
  descriptionKey: string;
  blocks: BlockDefinition[];
}

export const workflowTemplates: WorkflowTemplate[] = [
  {
    id: 'code-quality',
    icon: '\uD83E\uDDEA',
    nameKey: 'codeQuality',
    descriptionKey: 'codeQualityDescription',
    blocks: [
      { type: 'simple', config: { command: 'git status' } },
      { type: 'simple', config: { command: 'npm run lint' } },
      { type: 'retry', config: { command: 'npm test', max_retries: 3, delay: 5 } },
      { type: 'simple', config: { command: 'npm run build' } },
    ],
  },
  {
    id: 'document-review',
    icon: '\uD83D\uDCC4',
    nameKey: 'documentReview',
    descriptionKey: 'documentReviewDescription',
    blocks: [
      { type: 'skill-capability', config: { capability: 'grammar_check', input: '{{ document }}' } },
      { type: 'skill-capability', config: { capability: 'fact_check', input: '{{ previous_output }}' } },
      { type: 'skill-capability', config: { capability: 'summarize', input: '{{ previous_output }}' } },
    ],
  },
  {
    id: 'deploy-pipeline',
    icon: '\uD83D\uDE80',
    nameKey: 'deployPipeline',
    descriptionKey: 'deployPipelineDescription',
    blocks: [
      { type: 'conditional', config: { condition: 'test -f package.json', then_command: 'npm test', else_command: 'echo "No tests"' } },
      { type: 'simple', config: { command: 'npm run build' } },
      { type: 'simple', config: { command: 'npm run deploy' } },
    ],
  },
  {
    id: 'research-assistant',
    icon: '\uD83D\uDD0D',
    nameKey: 'researchAssistant',
    descriptionKey: 'researchAssistantDescription',
    blocks: [
      { type: 'skill-capability', config: { capability: 'web_search', input: '{{ query }}' } },
      { type: 'skill-capability', config: { capability: 'extract_key_points', input: '{{ previous_output }}' } },
      { type: 'skill-capability', config: { capability: 'cite_sources', input: '{{ previous_output }}' } },
    ],
  },
  {
    id: 'data-analysis',
    icon: '\uD83D\uDCCA',
    nameKey: 'dataAnalysis',
    descriptionKey: 'dataAnalysisDescription',
    blocks: [
      { type: 'skill-capability', config: { capability: 'analyze_statistics', input: '{{ dataset }}' } },
      { type: 'skill-capability', config: { capability: 'detect_anomalies', input: '{{ previous_output }}' } },
      { type: 'skill-capability', config: { capability: 'visualizations', input: '{{ previous_output }}' } },
    ],
  },
  {
    id: 'file-improve',
    icon: '✨',
    nameKey: 'fileImprove',
    descriptionKey: 'fileImproveDescription',
    blocks: [
      { type: 'simple', config: { command: 'ls -la /workspace/' } },
      { type: 'simple', config: { command: 'Schau dir die Dateien in deinem Projekt an. Lies die Datei {{ filepath }} und analysiere ihren Inhalt. Liste konkret auf: 1) Staerken 2) Schwaechen 3) Fehlende Aspekte' } },
      { type: 'simple', config: { command: 'Erstelle eine priorisierte Liste von Verbesserungsvorschlaegen. Sei konkret - keine vagen Hinweise, sondern umsetzbare Aenderungen.' } },
      { type: 'simple', config: { command: 'Wende die Verbesserungen an und gib die vollstaendig ueberarbeitete Version aus. Markiere die geaenderten Stellen.' } },
    ],
  },
  {
    id: 'research-enhance',
    icon: '🔬',
    nameKey: 'researchEnhance',
    descriptionKey: 'researchEnhanceDescription',
    blocks: [
      { type: 'simple', config: { command: 'find /workspace -type f \\( -name "*.md" -o -name "*.txt" -o -name "*.rst" \\) | head -20' } },
      { type: 'simple', config: { command: 'Lies die relevanten Dateien in deinem Projekt. Extrahiere die Kernaussagen und identifiziere inhaltliche Luecken oder Punkte die mehr Tiefe brauchen.' } },
      { type: 'simple', config: { command: 'Recherchiere zu den identifizierten Luecken. Finde aktuelle Fakten, Statistiken und Quellen die den Inhalt bereichern wuerden.' } },
      { type: 'simple', config: { command: 'Erstelle eine angereicherte Version der Hauptdatei. Integriere die recherchierten Informationen und fuege Quellenangaben hinzu.' } },
    ],
  },
  {
    id: 'iterative-refine',
    icon: '🔄',
    nameKey: 'iterativeRefine',
    descriptionKey: 'iterativeRefineDescription',
    blocks: [
      { type: 'simple', config: { command: 'cat /workspace/{{ filepath }}' } },
      { type: 'while', config: {
        condition_command: 'Bewerte die Qualitaet des Textes auf einer Skala von 1-10 (Klarheit, Vollstaendigkeit, Struktur). Antworte NUR mit "true" wenn unter 8, oder "false" wenn 8 oder hoeher.',
        command: 'Verbessere den Text. Fokussiere auf die groessten Schwaechen. Gib die vollstaendig ueberarbeitete Version aus.',
        max_iterations: 3,
      }},
      { type: 'simple', config: { command: 'Erstelle eine Zusammenfassung: Was wurde verbessert? Wie ist die finale Qualitaetsbewertung?' } },
    ],
  },
  {
    id: 'project-overview',
    icon: '🗂️',
    nameKey: 'projectOverview',
    descriptionKey: 'projectOverviewDescription',
    blocks: [
      { type: 'simple', config: { command: 'find /workspace -type f -not -path "*/node_modules/*" -not -path "*/.git/*" -not -name "*.lock" | head -50' } },
      { type: 'simple', config: { command: 'Verschaffe dir einen Ueberblick ueber das Projekt. Lies die wichtigsten Dateien (README, Konfigurationsdateien, Hauptdateien). Beschreibe: 1) Zweck des Projekts 2) Struktur 3) Verwendete Technologien' } },
      { type: 'simple', config: { command: 'Analysiere die Projektqualitaet: Was ist gut umgesetzt? Wo gibt es Verbesserungspotenzial? Fehlt Dokumentation?' } },
      { type: 'simple', config: { command: 'Erstelle eine strukturierte Zusammenfassung des Projekts mit konkreten Empfehlungen fuer naechste Schritte.' } },
    ],
  },
  {
    id: 'fact-check-pipeline',
    icon: '✅',
    nameKey: 'factCheckPipeline',
    descriptionKey: 'factCheckPipelineDescription',
    blocks: [
      { type: 'simple', config: { command: 'cat /workspace/{{ filepath }}' } },
      { type: 'simple', config: { command: 'Extrahiere alle faktischen Behauptungen, Zahlen und Statistiken. Nummeriere jede Behauptung.' } },
      { type: 'simple', config: { command: 'Pruefe jede Behauptung. Markiere als KORREKT, UNKLAR oder FALSCH mit Begruendung.' } },
      { type: 'conditional', config: {
        condition: 'Wurden Behauptungen als FALSCH oder UNKLAR markiert? Antworte nur mit true oder false.',
        then_command: 'Erstelle einen Korrekturbericht mit den richtigen Informationen fuer jede fehlerhafte Behauptung.',
        else_command: 'echo "Alle Fakten geprueft - keine Fehler gefunden."',
      }},
    ],
  },
  {
    id: 'translation-pipeline',
    icon: '🌐',
    nameKey: 'translationPipeline',
    descriptionKey: 'translationPipelineDescription',
    blocks: [
      { type: 'simple', config: { command: 'cat /workspace/{{ filepath }}' } },
      { type: 'simple', config: { command: 'Uebersetze den Text ins {{ target_language }}. Behalte Formatierung, Fachbegriffe und Struktur bei.' } },
      { type: 'simple', config: { command: 'Pruefe die Uebersetzung auf: 1) Sprachliche Natuerlichkeit 2) Fachbegriff-Konsistenz 3) Vollstaendigkeit. Liste alle Probleme auf.' } },
      { type: 'simple', config: { command: 'Korrigiere alle gefundenen Probleme und gib die finale Uebersetzung aus.' } },
    ],
  },
  {
    id: 'code-refactor',
    icon: '⚙️',
    nameKey: 'codeRefactor',
    descriptionKey: 'codeRefactorDescription',
    blocks: [
      { type: 'simple', config: { command: 'find /workspace -type f \\( -name "*.ts" -o -name "*.js" -o -name "*.py" -o -name "*.java" \\) -not -path "*/node_modules/*" | head -20' } },
      { type: 'simple', config: { command: 'Schau dir die Code-Dateien im Projekt an. Lies die Datei {{ filepath }} und analysiere: 1) Bugs und logische Fehler 2) Sicherheitsprobleme 3) Performance 4) Code-Qualitaet. Strukturiere nach Schweregrad.' } },
      { type: 'simple', config: { command: 'Erstelle fuer jedes Problem einen konkreten Verbesserungsvorschlag mit Code-Beispiel.' } },
      { type: 'simple', config: { command: 'Wende alle Verbesserungen an und gib die refactored Version aus. Kommentiere die wesentlichen Aenderungen.' } },
    ],
  },
  {
    id: 'topic-deep-dive',
    icon: '💡',
    nameKey: 'topicDeepDive',
    descriptionKey: 'topicDeepDiveDescription',
    blocks: [
      { type: 'simple', config: { command: 'ls -la /workspace/' } },
      { type: 'simple', config: { command: 'Schau dir das Projekt an und recherchiere das Thema "{{ topic }}" im Kontext dieses Projekts. Identifiziere die wichtigsten Aspekte und aktuelle Entwicklungen.' } },
      { type: 'simple', config: { command: 'Erstelle eine detaillierte Gliederung fuer einen Report zu diesem Thema.' } },
      { type: 'simple', config: { command: 'Schreibe den vollstaendigen Report. Jeder Abschnitt soll fundiert und gut strukturiert sein. Fuege am Ende ein Quellenverzeichnis hinzu.' } },
    ],
  },
];
