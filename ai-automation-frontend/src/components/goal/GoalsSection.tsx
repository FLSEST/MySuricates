import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, Plus, Target } from 'lucide-react';
import { Button, Card } from '../ui';
import { useGoals } from '../../hooks/useGoals';
import { useProjects } from '../../hooks/useProjects';
import { useT } from '../../i18n';
import { GoalCard } from './GoalCard';
import { GoalCreateForm } from './GoalCreateForm';

/** So viele aktive Ziele zeigt das Cockpit; alle stehen unter „Ziele“. */
const COCKPIT_LIMIT = 4;
/** Gewählter Projektfilter des Cockpits; leer = alle Projekte. */
const FILTER_KEY = 'mysuricates-goals-project-filter';

function readFilter(): string {
  try {
    return localStorage.getItem(FILTER_KEY) ?? '';
  } catch {
    return '';
  }
}

function storeFilter(projectId: string) {
  try {
    if (projectId) localStorage.setItem(FILTER_KEY, projectId);
    else localStorage.removeItem(FILTER_KEY);
  } catch {
    // privates Fenster oder gesperrter Speicher: der Filter gilt dann bis zum Neuladen
  }
}

/**
 * Ziele im Cockpit (TODO dc0566f8), kompakt: die aktiven Ziele mit Fortschritt, Zieldatum und
 * offenen Fragen, dazu Anlegen und der Weg zur Übersicht aller Ziele (TODO c6ea8f5c). Ein
 * Projektfilter (gemerkt, Standard „Alle Projekte“) zeigt nur die Ziele eines Projekts (c60bf914).
 */
export function GoalsSection() {
  const t = useT();
  const navigate = useNavigate();
  const [projectId, setProjectId] = useState(readFilter);
  const { data: projects = [] } = useProjects();
  const choices = projects.filter((project) => !project.archivedAt || project.id === projectId);
  // Ein gemerktes Projekt, das es nicht mehr gibt, filtert nicht still alles weg
  const filter = projectId && (projects.length === 0 || choices.some((project) => project.id === projectId)) ? projectId : '';
  const { data: goals = [], isLoading } = useGoals(undefined, filter || undefined);
  const [creating, setCreating] = useState(false);
  const active = goals.filter((goal) => goal.status === 'ACTIVE');
  const shown = active.slice(0, COCKPIT_LIMIT);

  return (
    <section aria-labelledby="goals-heading">
      <div className="flex items-center gap-2 mb-3">
        <Target className="w-4 h-4 text-accent" />
        <h2 id="goals-heading" className="text-sm font-semibold text-text uppercase tracking-wide">{t.goals.title}</h2>
        <span className="text-xs text-text-tertiary hidden sm:inline">{t.goals.subtitle}</span>
        <div className="ml-auto flex items-center gap-3">
          {choices.length > 1 && (
            <select aria-label={t.goals.filterProject} value={filter} data-testid="goals-project-filter"
              onChange={(event) => {
                setProjectId(event.target.value);
                storeFilter(event.target.value);
              }}
              className="input-base w-auto max-w-[10rem] cursor-pointer py-1 text-xs">
              <option value="">{t.goals.allProjects}</option>
              {choices.map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}
            </select>
          )}
          <Link to="/goals" className="inline-flex items-center gap-1 text-xs text-text-secondary hover:text-accent">
            {t.goals.allGoals}
            <ArrowRight className="w-3 h-3" />
          </Link>
          {!creating && (
            <Button size="sm" variant="secondary" onClick={() => setCreating(true)}>
              <Plus className="w-3.5 h-3.5 mr-1" />
              {t.goals.create}
            </Button>
          )}
        </div>
      </div>

      {creating && (
        <GoalCreateForm onCancel={() => setCreating(false)} onCreated={(goal) => navigate(`/goals/${goal.id}`)}
          projectId={filter || undefined} />
      )}

      {!isLoading && shown.length === 0 && !creating && (
        <Card>
          <p className="text-sm text-text-secondary">{goals.length === 0 ? t.goals.empty : t.goals.noActive}</p>
        </Card>
      )}

      {shown.length > 0 && (
        <div className="grid gap-3 md:grid-cols-2">
          {shown.map((goal) => (
            <GoalCard key={goal.id} goal={goal} onOpen={() => navigate(`/goals/${goal.id}`)} />
          ))}
        </div>
      )}
    </section>
  );
}
