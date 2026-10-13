import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import type { ContainerImagesOverview } from '../../hooks/useContainerImages';
import { Containers } from '../Containers';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const overview: ContainerImagesOverview = {
  docker: 'CONNECTED',
  allowlistActive: false,
  inContainer: false,
  socketSwitch: null,
  switchSource: null,
  images: [
    {
      image: 'automation-agent-devops:latest',
      shipped: true,
      purpose: { de: 'Werkzeuge für Sicherheit', en: 'Security tools' },
      base: 'ubuntu:24.04',
      tools: [
        { name: 'trivy', version: '0.74.0', source: 'release', arg: 'TRIVY_VERSION' },
        { name: 'gh', version: null, source: 'apt', arg: null },
      ],
      status: 'OUTDATED',
      builtAt: '2026-09-23T08:03:56Z',
      allowed: true,
      buildCommand: 'scripts/build-agent-images.sh agent-devops',
      workflows: [{ workflowId: 'wf-sec', workflowName: 'Security Audit', block: 'trivy', mode: 'TOOL' }],
      projectTypes: [],
    },
    {
      image: 'automation-agent-node:latest',
      shipped: true,
      purpose: { de: 'Node', en: 'Node environment' },
      base: 'node:22-slim',
      tools: [{ name: 'node', version: '22', source: 'base', arg: null }],
      status: 'CURRENT',
      builtAt: '2026-10-06T07:17:13Z',
      allowed: true,
      buildCommand: 'scripts/build-agent-images.sh agent-node',
      workflows: [],
      projectTypes: ['CODE', 'PERSONAL'],
    },
    {
      image: 'alpine:3',
      shipped: false,
      purpose: {},
      base: null,
      tools: [],
      status: 'MISSING',
      builtAt: null,
      allowed: false,
      buildCommand: null,
      workflows: [{ workflowId: 'wf-lint', workflowName: 'Lint', block: 'check › inner', mode: 'COMMAND' }],
      projectTypes: [],
    },
  ],
};

function card(image: string): HTMLElement {
  const cards = screen.getAllByTestId('container-image');
  const found = cards.find((element) => within(element).queryByText(image));
  if (!found) throw new Error(`no card for ${image}`);
  return found;
}

describe('Containers', () => {
  it('shows each image with status, tools, usage and a rebuild command only where it helps', async () => {
    server.use(http.get('/api/v1/container-images', () => HttpResponse.json(overview)));

    renderWithProviders(<Containers />);

    expect(await screen.findByText('automation-agent-devops:latest')).toBeInTheDocument();

    const devops = card('automation-agent-devops:latest');
    expect(within(devops).getByText('Outdated')).toBeInTheDocument();
    expect(within(devops).getByText('trivy 0.74.0')).toBeInTheDocument();
    expect(within(devops).getByText('gh')).toHaveAttribute('title', 'Installed with apt, not pinned');
    expect(within(devops).getByRole('link', { name: 'Security Audit' })).toHaveAttribute('href', '/workflows/wf-sec');
    expect(within(devops).getByText('Tool')).toBeInTheDocument();
    expect(within(devops).getByText('scripts/build-agent-images.sh agent-devops')).toBeInTheDocument();

    const node = card('automation-agent-node:latest');
    expect(within(node).getByText('Current')).toBeInTheDocument();
    expect(within(node).queryByText('scripts/build-agent-images.sh agent-node')).not.toBeInTheDocument();
    expect(within(node).getByText('Project containers of type Code, Personal')).toBeInTheDocument();

    const alpine = card('alpine:3');
    expect(within(alpine).getByText('Named by a workflow')).toBeInTheDocument();
    expect(within(alpine).getByText('Missing')).toBeInTheDocument();
    expect(within(alpine).getByText('Not allowed')).toBeInTheDocument();
    expect(within(alpine).getByText('check › inner')).toBeInTheDocument();

    expect(screen.getByText('1 shipped image(s) do not match this version. Rebuild them on the host:')).toBeInTheDocument();
    expect(screen.getByText('scripts/build-agent-images.sh')).toBeInTheDocument();
    expect(screen.getByText(/No list of allowed images is configured/)).toBeInTheDocument();
  });

  it('says why nothing can be checked when Docker is not set up', async () => {
    server.use(http.get('/api/v1/container-images', () => HttpResponse.json({
      ...overview,
      docker: 'NOT_CONFIGURED',
      allowlistActive: true,
      images: overview.images.map((image) => ({ ...image, status: 'UNKNOWN', builtAt: null })),
    })));

    renderWithProviders(<Containers />);

    expect(await screen.findByText(/Docker is not set up on this machine/)).toBeInTheDocument();
    expect(screen.getByText(/Only allowed images may run/)).toBeInTheDocument();
    expect(screen.queryByText(/do not match this version/)).not.toBeInTheDocument();
  });

  it('names the socket switch when the instance itself runs in a container', async () => {
    server.use(http.get('/api/v1/container-images', () => HttpResponse.json({
      ...overview,
      docker: 'NOT_CONFIGURED',
      inContainer: true,
      socketSwitch: 'TEST_DOCKER_SOCKET',
      switchSource: '/home/user/.config/mysuricates/staging.env',
      images: overview.images.map((image) => ({ ...image, status: 'UNKNOWN', builtAt: null })),
    })));

    renderWithProviders(<Containers />);

    expect(await screen.findByText(/This instance itself runs in a container, without the Docker socket/)).toBeInTheDocument();
    expect(screen.getByText('Switch it on in /home/user/.config/mysuricates/staging.env, then restart the instance:'))
      .toBeInTheDocument();
    expect(screen.getByText('TEST_DOCKER_SOCKET=1')).toBeInTheDocument();
    expect(screen.queryByText(/Docker is not set up on this machine/)).not.toBeInTheDocument();
  });

  it('asks for a restart with the socket when the starter named no switch', async () => {
    server.use(http.get('/api/v1/container-images', () => HttpResponse.json({
      ...overview,
      docker: 'NOT_CONFIGURED',
      inContainer: true,
      images: overview.images.map((image) => ({ ...image, status: 'UNKNOWN', builtAt: null })),
    })));

    renderWithProviders(<Containers />);

    expect(await screen.findByText('To use them, restart the instance with the socket mounted.')).toBeInTheDocument();
  });

  it('gives the tryout its own reason instead of a switch', async () => {
    server.use(http.get('/api/v1/container-images', () => HttpResponse.json({
      ...overview,
      docker: 'NOT_CONFIGURED',
      inContainer: true,
      tryout: true,
      images: overview.images.map((image) => ({ ...image, status: 'UNKNOWN', builtAt: null })),
    })));

    renderWithProviders(<Containers />);

    expect(await screen.findByText(/The tryout mounts nothing from the host/)).toBeInTheDocument();
    expect(screen.queryByText(/restart the instance with the socket mounted/)).not.toBeInTheDocument();
    expect(screen.queryByText(/secure default/)).not.toBeInTheDocument();
  });

  it('uses the German purpose and labels in German', async () => {
    useI18nStore.setState({ locale: 'de' });
    server.use(http.get('/api/v1/container-images', () => HttpResponse.json(overview)));

    renderWithProviders(<Containers />);

    expect(await screen.findByText('Werkzeuge für Sicherheit')).toBeInTheDocument();
    expect(within(card('automation-agent-devops:latest')).getByText('Veraltet')).toBeInTheDocument();
  });
});
