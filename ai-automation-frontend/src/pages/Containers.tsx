import { Link } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { Badge, type BadgeVariant } from '../components/ui/Badge';
import { Hint } from '../components/hints/Hint';
import { ConfigSnippet } from '../components/settings/ConfigSnippet';
import {
  useContainerImages,
  type ContainerImageStatus,
  type ContainerImageView,
  type ContainerImagesOverview,
} from '../hooks/useContainerImages';
import { useI18nStore, useT } from '../i18n';
import { apiErrorMessage } from '../api/client';
import { formatTimestamp } from '../utils/localeFormat';

const STATUS_VARIANT: Record<ContainerImageStatus, BadgeVariant> = {
  CURRENT: 'success',
  PRESENT: 'success',
  OUTDATED: 'warning',
  UNLABELED: 'warning',
  MISSING: 'danger',
  UNKNOWN: 'default',
};

/** Statuses that a (re)build fixes; only shipped images have a build command. */
const NEEDS_BUILD: ContainerImageStatus[] = ['OUTDATED', 'UNLABELED', 'MISSING'];

const BUILD_ALL = 'scripts/build-agent-images.sh';

/**
 * Container images workflows can run in (TODO 244e5de5): the shipped agent images with their tools
 * and whether the local build matches this version, plus every other image a workflow names, each
 * with the workflows and project containers that use it. Read-only: building stays a command.
 */
export function Containers() {
  const t = useT();
  const { data, isLoading, error } = useContainerImages();

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{t.containers.title}</h1>
        <p className="text-text-secondary mt-1">{t.containers.subtitle}</p>
      </div>

      <Hint id="containers-overview" />

      {isLoading ? (
        <div className="flex items-center gap-2 text-text-secondary text-sm">
          <Loader2 className="w-4 h-4 animate-spin" />
          {t.common.loading}
        </div>
      ) : error ? (
        <p className="text-sm text-danger">{apiErrorMessage(error, t.containers.loadError)}</p>
      ) : data ? (
        <>
          <StateNotes overview={data} />
          <div className="grid gap-4 xl:grid-cols-2">
            {data.images.map((image) => <ImageCard key={image.image} image={image} />)}
          </div>
        </>
      ) : null}
    </div>
  );
}

/**
 * Why no container can start here. An instance that itself runs in a container has no socket by default
 * (secure default), so "Docker is not set up on this machine" would be wrong there; it names the switch.
 */
function DockerMissing({ overview }: { overview: ContainerImagesOverview }) {
  const t = useT();
  if (overview.docker === 'UNAVAILABLE') {
    return (
      <p className="rounded-lg border border-warning/30 bg-warning-subtle px-4 py-3 text-sm text-warning">
        {t.containers.dockerUnavailable}
      </p>
    );
  }
  if (!overview.inContainer) {
    return (
      <p className="rounded-lg border border-warning/30 bg-warning-subtle px-4 py-3 text-sm text-warning">
        {t.containers.dockerNotConfigured}
      </p>
    );
  }
  if (overview.tryout) {
    return (
      <p className="rounded-lg border border-warning/30 bg-warning-subtle px-4 py-3 text-sm text-warning">
        {t.containers.dockerTryout}
      </p>
    );
  }
  return (
    <div className="rounded-lg border border-warning/30 bg-warning-subtle px-4 py-3 space-y-2">
      <p className="text-sm text-warning">{t.containers.dockerInContainer}</p>
      {overview.socketSwitch ? (
        <>
          {/* The path goes into the text: the snippet label is set in capitals, which would misspell it. */}
          <p className="text-sm text-warning break-all">
            {overview.switchSource
              ? t.containers.socketSwitchIn.replace('{source}', overview.switchSource)
              : t.containers.socketSwitch}
          </p>
          <ConfigSnippet label={t.containers.socketSwitchLabel} text={`${overview.socketSwitch}=1`} />
        </>
      ) : (
        <p className="text-sm text-warning">{t.containers.socketRestart}</p>
      )}
    </div>
  );
}

function StateNotes({ overview }: { overview: ContainerImagesOverview }) {
  const t = useT();
  const stale = overview.images.filter((image) => image.shipped && NEEDS_BUILD.includes(image.status));
  return (
    <div className="space-y-3">
      {overview.docker !== 'CONNECTED' && <DockerMissing overview={overview} />}
      {stale.length > 0 && (
        <div className="rounded-lg border border-warning/30 bg-warning-subtle px-4 py-3 space-y-2">
          <p className="text-sm text-warning">
            {t.containers.staleSummary.replace('{count}', String(stale.length))}
          </p>
          <ConfigSnippet label={t.containers.rebuildAll} text={BUILD_ALL} />
        </div>
      )}
      <p className="text-sm text-text-secondary">
        {overview.allowlistActive ? t.containers.allowlistActive : t.containers.allowlistOpen}
      </p>
    </div>
  );
}

function ImageCard({ image }: { image: ContainerImageView }) {
  const t = useT();
  const locale = useI18nStore((state) => state.locale);
  const purpose = image.purpose[locale] ?? image.purpose.en;
  const explanation = t.containers.statusExplain[image.status];
  const unused = image.workflows.length === 0 && image.projectTypes.length === 0;

  return (
    <section className="rounded-xl border border-card-border bg-surface-raised p-4 space-y-3" data-testid="container-image">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-mono text-sm font-semibold text-text break-all">{image.image}</p>
          <p className="text-xs text-text-tertiary">{image.shipped ? t.containers.shipped : t.containers.foreign}</p>
        </div>
        <div className="flex flex-wrap justify-end gap-1.5 shrink-0">
          <Badge variant={STATUS_VARIANT[image.status]} dot>{t.containers.status[image.status]}</Badge>
          {!image.allowed && <Badge variant="danger">{t.containers.notAllowed}</Badge>}
        </div>
      </div>

      {purpose && <p className="text-sm text-text-secondary">{purpose}</p>}
      {explanation && <p className="text-xs text-text-tertiary">{explanation}</p>}

      {image.base && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-text-tertiary">{t.containers.base}</dt>
          <dd className="font-mono text-xs text-text self-center">{image.base}</dd>
          <dt className="text-text-tertiary">{t.containers.tools}</dt>
          <dd className="flex flex-wrap gap-1.5">
            {image.tools.map((tool) => (
              <span
                key={tool.name}
                title={t.containers.source[tool.source]}
                className="rounded-md border border-border bg-surface px-1.5 py-0.5 font-mono text-xs text-text"
              >
                {tool.version ? `${tool.name} ${tool.version}` : tool.name}
              </span>
            ))}
          </dd>
        </dl>
      )}

      {image.builtAt && (
        <p className="text-xs text-text-tertiary">
          {t.containers.builtAt.replace('{date}', formatTimestamp(image.builtAt, locale))}
        </p>
      )}

      {image.buildCommand && NEEDS_BUILD.includes(image.status) && (
        <ConfigSnippet label={t.containers.rebuild} text={image.buildCommand} />
      )}

      <div>
        <p className="text-[11px] uppercase tracking-wide text-text-tertiary mb-1">{t.containers.usedBy}</p>
        {unused ? (
          <p className="text-sm text-text-tertiary">{t.containers.unused}</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {image.workflows.map((use) => (
              <li key={`${use.workflowId}-${use.block}`} className="flex flex-wrap items-center gap-2">
                <Link to={`/workflows/${use.workflowId}`} className="text-accent hover:underline">
                  {use.workflowName}
                </Link>
                <span className="text-text-tertiary">{use.block}</span>
                <Badge variant={use.mode === 'TOOL' ? 'info' : 'default'}>{t.containers.mode[use.mode]}</Badge>
              </li>
            ))}
            {image.projectTypes.length > 0 && (
              <li className="text-text-secondary">
                {t.containers.projectContainers.replace('{types}', image.projectTypes
                  .map((type) => t.projects.types[type as keyof typeof t.projects.types] ?? type)
                  .join(', '))}
              </li>
            )}
          </ul>
        )}
      </div>
    </section>
  );
}
